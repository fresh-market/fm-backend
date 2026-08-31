package com.freshmarket.admin.internal.service;

import com.freshmarket.admin.internal.dto.AdminLoginRequest;
import com.freshmarket.admin.internal.dto.AdminLoginResponse;
import com.freshmarket.admin.internal.dto.AdminLoginResult;
import com.freshmarket.admin.internal.entity.Admin;
import com.freshmarket.admin.internal.exception.AdminErrorCode;
import com.freshmarket.admin.internal.exception.AdminException;
import com.freshmarket.admin.internal.logging.SafeExceptionLog;
import com.freshmarket.admin.internal.repository.AdminRepository;
import com.freshmarket.common.auth.jwt.AccessTokenValidAfterRepository;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.common.auth.opaque.OpaqueTokenGenerator;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.opaque.TokenHasher;
import com.freshmarket.common.exception.CommonErrorCode;
import com.freshmarket.common.logging.PiiMasker;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/*
 * 관리자 로그인과 로그아웃을 다룬다.
 * 토큰 재발급과 비밀번호 변경은 별도 PR이다 (auth.md 참고).
 *
 * JWT 서명·액세스 토큰 발급은 member/admin이 공유하는 common.auth.jwt.JwtTokenProvider를 그대로 쓴다.
 * 액세스 토큰 유효기간도 JwtTokenProvider가 관리한다.
 * 리프레시 토큰은 member와 같은 공통 RefreshTokenRepository(Redis)에 저장한다.
 *
 * 로그인은 최초 발급만 담당하고, Rotation은 별도 재발급 API에서 compareAndRotate()로 처리한다.
 *
 * 로그인 시 Refresh Token은 DB를 필수 백업 저장소로 사용하고 Redis에도 기록한다.
 * DB 갱신은 AdminLoginTransactionService의 짧은 트랜잭션에서 처리하고 Redis I/O는 트랜잭션 밖에서 수행한다.
 * DB 백업이 확정된 뒤에만 Redis 저장과 로그인 응답을 진행하므로 Redis 전용 부분 성공 상태를 만들지 않는다.
 * Redis 장애 시에는 이미 확정된 DB 백업을 fallback으로 사용해 로그인은 성공시킨다.
 *
 * 로그아웃 시 Refresh Token의 Redis 정리와 DB 폐기는 AdminRefreshTokenCleanupService에 있다.
 * 서버에서는 저장소 작업을 한 번만 시도한다. 실패하면 별도 실패 테이블이나 스케줄러에 넘기지 않고
 * 실패 로그를 남긴 뒤 ADMIN-010으로 응답한다. 이후 재시도는 클라이언트의 새 로그아웃 요청으로 수행한다.
 */
@Slf4j
@Service
public class AdminAuthService {

    // 실제 계정과 무관한 값이다. 계정이 없을 때도 이 해시로 BCrypt 를 돌려 응답 시간을 맞춘다 (SEC-6-04)
    private static final String DUMMY_PASSWORD_SOURCE = "dummy-password-for-constant-time-comparison";
    // 로그인 실패 로그 포맷
    private static final String LOG_ADMIN_LOGIN_FAILED = "event=ADMIN_LOGIN success=false loginId={}";
    // Access Token 차단 로그에서 반복되는 필드 포맷
    private static final String LOG_FIELDS_ROLE_ADMIN_ID = "role={} adminId={}";
    // 예외 타입 로그에서 반복되는 필드 포맷
    private static final String LOG_FIELD_ERROR_TYPE = " errorType={}";

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AdminLoginTransactionService adminLoginTransactionService;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final AdminLogoutTransactionService adminLogoutTransactionService;
    private final AdminRefreshTokenCleanupService adminRefreshTokenCleanupService;
    private final Clock clock;
    private final long refreshTokenValiditySeconds;
    private final String dummyPasswordHash;

    public AdminAuthService(
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            AccessTokenValidAfterRepository accessTokenValidAfterRepository,
            AdminLogoutTransactionService adminLogoutTransactionService,
            AdminLoginTransactionService adminLoginTransactionService,
            AdminRefreshTokenCleanupService adminRefreshTokenCleanupService,
            Clock clock,
            @Value("${ADMIN_REFRESH_TOKEN_VALIDITY_SECONDS:86400}") long refreshTokenValiditySeconds) {

        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.accessTokenValidAfterRepository = accessTokenValidAfterRepository;
        this.adminLogoutTransactionService = adminLogoutTransactionService;
        this.adminLoginTransactionService = adminLoginTransactionService;
        this.adminRefreshTokenCleanupService = adminRefreshTokenCleanupService;
        this.clock = clock;
        this.refreshTokenValiditySeconds = refreshTokenValiditySeconds;

        // 같은 인코더로 미리 만들어 둬야 진짜 비밀번호 검증과 연산 비용(코스트 팩터)이 완전히 같다
        this.dummyPasswordHash = passwordEncoder.encode(DUMMY_PASSWORD_SOURCE);
    }

    public AdminLoginResult login(AdminLoginRequest request) {
        Objects.requireNonNull(request, "request");

        Optional<Admin> found = adminRepository.findByLoginId(request.loginId());

        /*
         * 계정이 없어도 항상 BCrypt 를 돌린다 (SEC-6-04, auth.md "관리자 > 로그인" 절).
         * 계정이 없을 때 BCrypt 자체를 건너뛰면, 있을 때와 없을 때의 응답 시간이 갈려서
         * 그 시간 차이가 그 자체로 아이디 존재 여부를 흘리는 타이밍 사이드채널이 된다.
         *
         * found 가 비어 있으면 아래 단락 값과 무관하게 항상 LOGIN_FAILED 로 던진다
         * (단락 평가로 그렇게 되어 있다). dummyPasswordHash 비교는 오직 시간을 맞추기 위한 것이다.
         */
        String hashToCompare = found.map(Admin::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCompare);

        if (found.isEmpty() || !passwordMatches) {
            log.warn(LOG_ADMIN_LOGIN_FAILED, maskLoginId(request.loginId()));
            throw new AdminException(AdminErrorCode.LOGIN_FAILED);
        }

        /*
         * 비활성 계정도 외부에는 일반 로그인 실패와 같은 응답으로 처리한다 (SEC-6-04).
         * 아이디/비밀번호가 맞더라도 계정 상태를 별도 코드로 알려주면 공격자가 계정 상태를 추측할 수 있으므로
         * LOGIN_FAILED 로 통일한다.
         */
        Admin admin = found.get();
        if (!admin.isActive()) {
            log.warn(LOG_ADMIN_LOGIN_FAILED, maskLoginId(request.loginId()));
            throw new AdminException(AdminErrorCode.LOGIN_FAILED);
        }

        /*
         * Refresh Token 원문은 한 번만 만들고, 같은 해시를 DB 백업과 Redis에 사용한다.
         * JWT/opaque 토큰 생성과 Redis I/O는 DB 트랜잭션 밖에서 수행한다.
         *
         * 짧은 DB 트랜잭션은 AdminLoginTransactionService가 관리자 행 잠금, 활성 상태 재확인,
         * Refresh Token 백업 갱신까지만 담당한다.
         *
         * DB 백업이 확정된 뒤 Redis 저장을 시도하고,
         * Redis 장애 시에는 이미 확정된 DB 백업을 fallback으로 사용한다.
         */
        String rawRefreshToken = OpaqueTokenGenerator.generate();
        String refreshTokenHash = TokenHasher.sha256(rawRefreshToken);
        Duration refreshTtl = Duration.ofSeconds(refreshTokenValiditySeconds);
        LocalDateTime refreshTokenExpiresAt = LocalDateTime.now(clock).plus(refreshTtl);

        AdminLoginTransactionService.LoginDbState dbState;
        try {
            dbState = adminLoginTransactionService.issueRefreshToken(
                    admin.getId(), refreshTokenHash, refreshTokenExpiresAt);
        } catch (DataAccessException | TransactionException e) {
            log.error(
                    "event=ADMIN_LOGIN_DB_BACKUP_SAVE_FAILED adminId={} — DB 백업 미확정으로 로그인 중단",
                    admin.getId(), e);
            throw new AdminException(CommonErrorCode.INTERNAL_ERROR, e);
        }

        Long adminId = dbState.adminId();
        String loginId = dbState.loginId();
        String name = dbState.name();
        var roleValue = dbState.role();
        String role = roleValue.toAuthority();

        String accessToken;
        try {
            accessToken = jwtTokenProvider.createAccessToken(adminId, TokenType.ADMIN, role);
        } catch (RuntimeException e) {
            // DB 백업까지 성공했지만 JWT 생성에 실패하면 이번 로그인에서 저장한 RT 해시만 조건부 제거한다.
            adminLoginTransactionService.clearRefreshTokenIfMatches(adminId, refreshTokenHash);
            throw e;
        }

        try {
            refreshTokenRepository.save(
                    rawRefreshToken, adminId, role, TokenType.ADMIN, false, refreshTtl);
        } catch (DataAccessException e) {
            // DB 백업은 이미 확정됐으므로 Redis 장애 시에도 DB fallback으로 로그인은 유지한다.
            log.warn("event=ADMIN_LOGIN_REDIS_SAVE_FAILED adminId={} — DB fallback으로 로그인 유지",
                    adminId, e);
        }

        AdminLoginResponse response = new AdminLoginResponse(
                jwtTokenProvider.getAccessTokenValidityMs() / 1000,
                new AdminLoginResponse.AdminSummary(loginId, name, roleValue));

        log.info("event=ADMIN_LOGIN success=true adminId={} loginId={}",
                adminId, maskLoginId(loginId));

        // 두 토큰 원문은 응답 본문이 아니라 컨트롤러가 만드는 HttpOnly 쿠키로만 나간다
        return new AdminLoginResult(response, accessToken, rawRefreshToken, refreshTokenValiditySeconds);
    }

    /*
     * 관리자 로그아웃. DB Refresh Token 폐기 -> Redis Refresh Token 정리 -> Access Token 차단 순서로 수행한다.
     * DB/Redis 작업은 서버에서 각각 한 번만 시도하고, 실패를 별도 DB 테이블이나 스케줄러로 재처리하지 않는다.
     * 하나라도 실패하면 실패 로그를 남기고 ADMIN-010으로 종료한다.
     * 클라이언트가 다시 로그아웃을 요청하면 멱등적인 폐기/삭제 로직을 처음부터 다시 수행한다.
     */
    public void logout(Long adminId, String role) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(role, "role");

        // 클라이언트 재요청에서 DB RT가 이미 폐기된 상태일 수 있으므로 Redis active hash를 먼저 확인한다.
        // Redis 조회 자체가 실패하면 로그아웃 상태를 확정할 수 없으므로 즉시 실패 응답한다.
        Optional<String> activeTokenHash = findActiveRefreshTokenHashOrThrow(role, adminId);

        AdminLogoutTransactionService.LogoutDbState dbState =
                adminRefreshTokenCleanupService.revokeDbOnce(adminId);

        boolean dbFailed = dbState == null;

        // 이전 요청에서 DB 폐기만 성공하고 Redis 정리가 실패했을 수 있다.
        // 재요청 시 DB 해시는 이미 null이므로 Redis active hash를 fallback으로 사용해 남은 RT를 정리한다.
        String tokenHash = dbState != null && dbState.refreshTokenHash() != null
                ? dbState.refreshTokenHash()
                : activeTokenHash.orElse(null);

        // DB와 Redis 모두 이미 RT가 없는 재요청은 정리가 끝난 상태이므로 성공으로 본다.
        boolean redisOk = tokenHash == null
                || adminRefreshTokenCleanupService.cleanupRedisOnce(role, adminId, tokenHash);

        boolean redisFailed = !redisOk;

        if (dbFailed || redisFailed) {
            log.error("event=ADMIN_LOGOUT_REFRESH_TOKEN_CLEANUP_FAILED role={} adminId={} redisFailed={} dbFailed={}",
                    role, adminId, redisFailed, dbFailed);
            throw new AdminException(AdminErrorCode.LOGOUT_FAILED);
        }

        LocalDateTime cutoff = LocalDateTime.now(clock);
        invalidateAccessTokenOrThrow(role, adminId, cutoff);

        // RT 폐기와 Access Token 차단까지 끝났다면 보안상 로그아웃은 이미 완료된 상태다.
        // 감사 로그 저장 실패 때문에 ADMIN-010을 반환하면 차단된 Access Token으로 클라이언트가 재시도할 수 없으므로,
        // 감사 로그는 best-effort로 기록하고 실패 시 애플리케이션 로그만 남긴다.
        try {
            adminLogoutTransactionService.recordSuccess(adminId);
        } catch (DataAccessException e) {
            log.error("event=ADMIN_LOGOUT_AUDIT_LOG_FAILED adminId={} errorType={}",
                    adminId, SafeExceptionLog.errorType(e), SafeExceptionLog.stackTrace(e));
        }

        log.info("event=ADMIN_LOGOUT success=true adminId={}", adminId);
    }

    /*
     * 클라이언트 재요청을 위해 Redis active hash를 보조 조회한다.
     * 조회 실패는 별도 재처리로 넘기지 않고 ADMIN-010으로 즉시 응답한다.
     */
    private Optional<String> findActiveRefreshTokenHashOrThrow(String role, Long adminId) {
        try {
            return refreshTokenRepository.findActiveHash(role, adminId);
        } catch (DataAccessException e) {
            log.error("event=ADMIN_LOGOUT_ACTIVE_REFRESH_TOKEN_LOOKUP_FAILED "
                            + LOG_FIELDS_ROLE_ADMIN_ID
                            + LOG_FIELD_ERROR_TYPE,
                    role, adminId, SafeExceptionLog.errorType(e), SafeExceptionLog.stackTrace(e));
            throw new AdminException(AdminErrorCode.LOGOUT_FAILED);
        }
    }

    /*
     * 로그아웃 이전에 발급된 Access Token을 사용할 수 없도록 Redis에 valid-after 커트라인을 한 번 저장한다.
     * 서버 내부 재시도는 하지 않는다. Redis 작업이 실패하면 로그를 남기고 ADMIN-010으로 응답하며,
     * 이후 재시도는 클라이언트의 새 로그아웃 요청에 맡긴다.
     */
    private void invalidateAccessTokenOrThrow(
            String role, Long adminId, LocalDateTime cutoff) {

        Duration ttl = Duration.ofMillis(
                jwtTokenProvider.getAccessTokenValidityMs());

        try {
            accessTokenValidAfterRepository.invalidateBefore(role, adminId, cutoff, ttl);
        } catch (DataAccessException e) {
            log.error("event=ADMIN_ACCESS_TOKEN_INVALIDATION_FAILED "
                            + LOG_FIELDS_ROLE_ADMIN_ID
                            + LOG_FIELD_ERROR_TYPE,
                    role, adminId, SafeExceptionLog.errorType(e), SafeExceptionLog.stackTrace(e));
            throw new AdminException(AdminErrorCode.LOGOUT_FAILED);
        }
    }

    private String maskLoginId(String loginId) { return PiiMasker.maskGeneric(loginId, 2, 1); }
}