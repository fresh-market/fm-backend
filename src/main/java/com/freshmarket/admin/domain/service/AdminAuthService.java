package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.dto.AdminLoginRequest;
import com.freshmarket.admin.domain.dto.AdminLoginResponse;
import com.freshmarket.admin.domain.dto.AdminLoginResult;
import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.logging.SafeExceptionLog;
import com.freshmarket.admin.domain.repository.AdminRepository;
import com.freshmarket.admin.domain.retry.FullJitterRetryPolicy;
import com.freshmarket.common.auth.jwt.AccessTokenValidAfterRepository;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.common.auth.opaque.OpaqueTokenGenerator;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.opaque.TokenHasher;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

import com.freshmarket.common.logging.PiiMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자 로그인과 로그아웃을 다룬다.
 * 토큰 재발급과 비밀번호 변경은 별도 PR이다 (auth.md 참고).
 *
 * (merge: feat/member-auth와 합치며 추가) JWT 서명·액세스 토큰 발급은 member/admin이 공유하는
 * common.auth.jwt.JwtTokenProvider를 그대로 쓴다.
 * 액세스 토큰 유효기간도 JwtTokenProvider가 관리한다.
 * 리프레시 토큰은 member와 같은 공통 RefreshTokenRepository(Redis)에 저장한다.
 * 로그인은 최초 발급만 담당하고, Rotation은 별도 재발급 API에서 처리한다.
 *
 * Refresh Token의 Redis 정리와 DB 폐기(재시도 포함)는 AdminRefreshTokenCleanupService에 있다.
 * logout()에서 그 즉시 재시도(각 3회)까지 실패하면 AdminLogoutFailureService에 기록해두고,
 * AdminLogoutFailureScheduler가 10분 간격으로 재시도한다.
 */
@Slf4j
@Service
public class AdminAuthService {

    // 실제 계정과 무관한 값이다. 계정이 없을 때도 이 해시로 BCrypt 를 돌려 응답 시간을 맞춘다 (SEC-6-04)
    private static final String DUMMY_PASSWORD_SOURCE = "dummy-password-for-constant-time-comparison";

    // Access Token 차단 커트라인 반영 여부를 확인할 때 사용하는 최소 시간 오프셋
    private static final long CUTOFF_CONFIRMATION_OFFSET_NANOS = 1L;

    // Access Token 차단 Redis 재시도도 요청 예산 안에서 Full Jitter 지수 백오프를 적용한다.
    private static final int MAX_ACCESS_TOKEN_INVALIDATION_ATTEMPTS = 3;
    private static final Duration ACCESS_TOKEN_RETRY_BUDGET = Duration.ofMillis(500);
    private static final Duration ACCESS_TOKEN_RETRY_BASE_DELAY = Duration.ofMillis(25);
    private static final Duration ACCESS_TOKEN_RETRY_MAX_DELAY = Duration.ofMillis(100);

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
    private final long refreshTokenValiditySeconds;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final AdminLogoutTransactionService adminLogoutTransactionService;
    private final AdminRefreshTokenCleanupService adminRefreshTokenCleanupService;
    private final AdminLogoutFailureService adminLogoutFailureService;
    private final AdminAuditFailureService adminAuditFailureService;
    private final Clock clock;
    private final FullJitterRetryPolicy retryPolicy;
    private final String dummyPasswordHash;

    public AdminAuthService(
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            AccessTokenValidAfterRepository accessTokenValidAfterRepository,
            AdminLogoutTransactionService adminLogoutTransactionService,
            AdminRefreshTokenCleanupService adminRefreshTokenCleanupService,
            AdminLogoutFailureService adminLogoutFailureService,
            AdminAuditFailureService adminAuditFailureService,
            Clock clock,
            FullJitterRetryPolicy retryPolicy,
            @Value("${ADMIN_REFRESH_TOKEN_VALIDITY_SECONDS:86400}") long refreshTokenValiditySeconds) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.accessTokenValidAfterRepository = accessTokenValidAfterRepository;
        this.adminLogoutTransactionService = adminLogoutTransactionService;
        this.adminRefreshTokenCleanupService = adminRefreshTokenCleanupService;
        this.adminLogoutFailureService = adminLogoutFailureService;
        this.adminAuditFailureService = adminAuditFailureService;
        this.clock = clock;
        this.retryPolicy = retryPolicy;
        this.refreshTokenValiditySeconds = refreshTokenValiditySeconds;
        // 같은 인코더로 미리 만들어 둬야 진짜 비밀번호 검증과 연산 비용(코스트 팩터)이 완전히 같다
        this.dummyPasswordHash = passwordEncoder.encode(DUMMY_PASSWORD_SOURCE);
    }

    @Transactional(timeout = 5)
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
         * 아이디/비밀번호가 맞더라도 계정 상태를 별도 코드로 알려주면 공격자가 계정 상태를 추측할 수 있으므로 LOGIN_FAILED 로 통일한다.
         */
        Admin admin = found.get();
        if (!admin.isActive()) {
            log.warn(LOG_ADMIN_LOGIN_FAILED, maskLoginId(request.loginId()));
            throw new AdminException(AdminErrorCode.LOGIN_FAILED);
        }

        /*
         * 비밀번호 검증이 끝난 성공 후보에 대해서만 관리자 행을 잠근다.
         * findByLoginId 자체에 PESSIMISTIC_WRITE를 걸면 존재하는 계정에 대한 비밀번호 실패 요청도
         * 느린 BCrypt 비교 동안 쓰기 잠금을 점유하므로, 실제 Refresh Token 상태를 갱신하기 직전에만 잠근다.
         * 잠금을 얻는 사이 계정 상태가 바뀔 수 있으므로 잠금 조회 뒤 활성 상태도 다시 확인한다.
         */
        Admin lockedAdmin = adminRepository.findByIdForUpdate(admin.getId())
                .orElseThrow(() -> new AdminException(AdminErrorCode.LOGIN_FAILED));
        if (!lockedAdmin.isActive()) {
            log.warn(LOG_ADMIN_LOGIN_FAILED, maskLoginId(request.loginId()));
            throw new AdminException(AdminErrorCode.LOGIN_FAILED);
        }

        String accessToken = jwtTokenProvider.createAccessToken(
                lockedAdmin.getId(), TokenType.ADMIN, lockedAdmin.getRole().toAuthority());

        String rawRefreshToken = OpaqueTokenGenerator.generate();
        Duration refreshTtl = Duration.ofSeconds(refreshTokenValiditySeconds);
        refreshTokenRepository.save(
                rawRefreshToken,
                lockedAdmin.getId(),
                lockedAdmin.getRole().toAuthority(),
                TokenType.ADMIN,
                false,
                refreshTtl);

        /* Redis의 active key가 유실돼도 로그아웃 시 실제 Refresh Token 레코드를 찾을 수 있도록 DB에도 해시와 만료시각을 백업한다.
         * 평문 토큰은 DB에 저장하지 않는다.
         */
        lockedAdmin.issueRefreshToken(
                TokenHasher.sha256(rawRefreshToken),
                LocalDateTime.now(clock).plus(refreshTtl));

        AdminLoginResponse response = new AdminLoginResponse(
                jwtTokenProvider.getAccessTokenValidityMs() / 1000,
                new AdminLoginResponse.AdminSummary(
                        lockedAdmin.getLoginId(), lockedAdmin.getName(), lockedAdmin.getRole()));

        log.info("event=ADMIN_LOGIN success=true adminId={} loginId={}",
                lockedAdmin.getId(), maskLoginId(lockedAdmin.getLoginId()));

        // 두 토큰 원문은 응답 본문이 아니라 컨트롤러가 만드는 HttpOnly 쿠키로만 나간다
        return new AdminLoginResult(response, accessToken, rawRefreshToken, refreshTokenValiditySeconds);
    }

    /*
     * 관리자 로그아웃. DB의 Refresh Token 백업을 먼저 폐기한 뒤 Redis 정리와 Access Token 차단을
     * 수행한다 — 각각 AdminRefreshTokenCleanupService가 즉시 재시도(3회씩)까지 담당한다.
     *
     * Refresh Token은 DB를 최종 판정 기준으로 사용한다. 즉시 재시도(3회)까지 다 실패하면
     * AdminLogoutFailureService에 기록해두고, 로그아웃 자체는 계속 진행한다 — 아웃박스에 남았으니
     * 스케줄러가 다시 시도할 것이고, 여기서 실패로 응답을 막을 이유는 없다(Access Token 차단은
     * 별개로 확정되고, DB는 이미 최종 판정 기준으로 재발급을 거부할 근거를 갖고 있다).
     *
     * Access Token은 기존 공용 JwtAuthenticationFilter의 Redis fail-open 정책을 유지하되,
     * 이 로그아웃 요청 자체는 차단 커트라인 저장 결과를 확정하지 못하면 성공으로 응답하지 않는다.
     */
    public void logout(Long adminId, String role) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(role, "role");

        // DB 폐기가 실패하면 트랜잭션 밖으로 기존 해시를 반환받을 수 없으므로, 지연 재시도가
        // 새 로그인 RT를 잘못 지우지 않게 실패 대상의 해시를 Redis active index에서 먼저 보조 확보한다.
        // 조회 실패는 로그아웃 자체를 막지 않고, DB 폐기가 성공하면 DB에서 얻은 해시를 우선 사용한다.
        String activeTokenHash = findActiveRefreshTokenHashSafely(role, adminId);

        AdminLogoutTransactionService.LogoutDbState dbState =
                adminRefreshTokenCleanupService.revokeDbWithRetry(adminId);

        boolean dbFailed = dbState == null;
        String tokenHash = dbFailed ? activeTokenHash : dbState.refreshTokenHash();

        boolean redisOk = adminRefreshTokenCleanupService.cleanupRedisWithRetry(role, adminId, tokenHash);
        boolean redisFailed = !redisOk;

        if (dbFailed || redisFailed) {
            adminLogoutFailureService.recordFailure(adminId, tokenHash, redisFailed, dbFailed);
        }

        LocalDateTime cutoff = LocalDateTime.now(clock);
        invalidateAccessTokenOrThrow(role, adminId, cutoff);

        /*
         * Access Token 차단까지 확정된 뒤 성공 감사 로그를 기록한다. 직접 저장이 실패하면
         * admin_audit_failure 아웃박스에 같은 감사 이벤트를 내구성 있게 남기고 배치가 재시도한다.
         * 아웃박스 기록까지 실패하면 예외를 숨기지 않아 감사 행위가 완전히 유실된 채 204가 나가지 않는다.
         */
        try {
            adminLogoutTransactionService.recordSuccess(adminId);
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_LOGOUT_AUDIT_LOG_FAILED adminId={} errorType={} — durable outbox에 기록",
                    adminId, SafeExceptionLog.errorType(e), SafeExceptionLog.stackTrace(e));
            // 이 기록까지 실패하면 예외를 숨기지 않는다. 감사 행위가 DB와 outbox 양쪽에서
            // 모두 유실된 상태로 204를 반환하는 것을 막기 위해 호출자에게 실패를 드러낸다.
            adminAuditFailureService.recordFailure(
                    adminId, "ADMIN_LOGOUT", String.valueOf(adminId), null);
        }

        log.info("event=ADMIN_LOGOUT success=true adminId={}", adminId);
    }

    /*
     * DB 폐기 실패 시 아웃박스에 과거 RT 해시를 남길 수 있도록 Redis active index를 보조 조회한다.
     * 조회가 실패하면 null을 반환한다. null인 실패 건은 지연 재시도에서 조건 없는 DB 삭제를 하지 않는다.
     */
    private String findActiveRefreshTokenHashSafely(String role, Long adminId) {
        try {
            return refreshTokenRepository.findActiveHash(role, adminId).orElse(null);
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_LOGOUT_ACTIVE_REFRESH_TOKEN_LOOKUP_FAILED "
                            + LOG_FIELDS_ROLE_ADMIN_ID + LOG_FIELD_ERROR_TYPE,
                    role, adminId, SafeExceptionLog.errorType(e), SafeExceptionLog.stackTrace(e));
            return null;
        }
    }

    /*
     * 로그아웃 이전에 발급된 Access Token을 사용할 수 없도록 Redis에 valid-after 커트라인을 저장한다.
     * timeout/연결 실패의 경우 Redis가 실제로 반영했을 가능성이 있으므로 후속 조회로 확인하고,
     * 아직 미반영이면 요청 예산 안에서 Full Jitter 지수 백오프를 두고 최대 3회까지 저장을 시도한다.
     */
    private void invalidateAccessTokenOrThrow(
            String role,
            Long adminId,
            LocalDateTime cutoff) {

        Duration ttl = Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs());
        long deadlineNanos = retryPolicy.deadline(ACCESS_TOKEN_RETRY_BUDGET);
        DataAccessException lastUnknownFailure = null;

        for (int attempt = 1; attempt <= MAX_ACCESS_TOKEN_INVALIDATION_ATTEMPTS; attempt++) {
            try {
                accessTokenValidAfterRepository.invalidateBefore(role, adminId, cutoff, ttl);
                return;

            } catch (QueryTimeoutException | DataAccessResourceFailureException e) {
                lastUnknownFailure = e;
                log.warn(
                        "event=ADMIN_ACCESS_TOKEN_INVALIDATION_UNKNOWN "
                                + LOG_FIELDS_ROLE_ADMIN_ID
                                + " attempt={} errorType={}",
                        role,
                        adminId,
                        attempt,
                        SafeExceptionLog.errorType(e),
                        SafeExceptionLog.stackTrace(e));

                // timeout/연결 단절은 명령이 Redis에 반영된 뒤 응답만 잃었을 수도 있으므로 먼저 확인한다.
                if (isAccessTokenInvalidationConfirmed(role, adminId, cutoff)) {
                    return;
                }

            } catch (DataAccessException e) {
                throw logoutFailed(role, adminId, e);
            }

            if (attempt < MAX_ACCESS_TOKEN_INVALIDATION_ATTEMPTS
                    && !retryPolicy.waitBeforeRetry(
                    attempt, deadlineNanos,
                    ACCESS_TOKEN_RETRY_BASE_DELAY, ACCESS_TOKEN_RETRY_MAX_DELAY)) {
                break;
            }
        }

        // 재시도 예산 또는 횟수를 다 썼더라도 마지막 상태 확인에서 반영이 확인되면 성공으로 본다.
        if (!isAccessTokenInvalidationConfirmed(role, adminId, cutoff)) {
            throw logoutFailed(role, adminId, lastUnknownFailure);
        }
    }

    /*
     * 저장된 커트라인이 cutoff 이상인지 간접적으로 확인한다.
     * cutoff 직전 시각의 토큰이 더 이상 유효하지 않다면 logout 시 기록한 커트라인이 반영된 것으로 볼 수 있다.
     */
    private boolean isAccessTokenInvalidationConfirmed(
            String role,
            Long adminId,
            LocalDateTime cutoff) {

        try {
            return !accessTokenValidAfterRepository
                    .isValidAfter(
                            role,
                            adminId,
                            cutoff.minusNanos(
                                    CUTOFF_CONFIRMATION_OFFSET_NANOS));

        } catch (DataAccessException e) {

            log.warn(
                    "event=ADMIN_ACCESS_TOKEN_INVALIDATION_CONFIRM_FAILED "
                            + LOG_FIELDS_ROLE_ADMIN_ID
                            + LOG_FIELD_ERROR_TYPE,
                    role,
                    adminId,
                    SafeExceptionLog.errorType(e),
                    SafeExceptionLog.stackTrace(e));

            return false;
        }
    }

    // Access Token 차단 결과를 확정하지 못했을 때 ADMIN-010 예외를 생성한다.
    private AdminException logoutFailed(
            String role,
            Long adminId,
            DataAccessException cause) {

        log.error(
                "event=ADMIN_ACCESS_TOKEN_INVALIDATION_FAILED "
                        + LOG_FIELDS_ROLE_ADMIN_ID
                        + LOG_FIELD_ERROR_TYPE,
                role,
                adminId,
                SafeExceptionLog.errorType(cause),
                SafeExceptionLog.stackTrace(cause));

        // DataAccessException을 cause로 연결하면 GlobalExceptionHandler가 BusinessException의
        // 전체 스택을 남길 때 공급자 SQL/상세 메시지가 함께 기록될 수 있으므로 외부 도메인 예외에는 싣지 않는다.
        return new AdminException(AdminErrorCode.LOGOUT_FAILED);
    }

    private String maskLoginId(String loginId) { return PiiMasker.maskGeneric(loginId, 2, 1); }
}