package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.dto.AdminLoginRequest;
import com.freshmarket.admin.domain.dto.AdminLoginResponse;
import com.freshmarket.admin.domain.dto.AdminLoginResult;
import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminRepository;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.opaque.OpaqueTokenGenerator;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.jwt.TokenType;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

import com.freshmarket.common.auth.opaque.TokenHasher;
import com.freshmarket.common.logging.PiiMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/*
 * 관리자 로그인만 다룬다. 로그아웃, 토큰 재발급, 비밀번호 변경은 별도 PR 이다 (auth.md 참고).
 *
 * (merge: feat/member-auth와 합치며 추가) JWT 서명·액세스 토큰 발급은 member/admin이 공유하는
 * common.auth.jwt.JwtTokenProvider를 그대로 쓴다 — admin이 따로 두던 common.security.JwtTokenProvider와
 * 거의 동일한 구현을 독립적으로 만들었던 것이라, 중복을 없애고 이쪽으로 통합했다.
 * 액세스 토큰 유효기간도 이제 JwtTokenProvider가 갖고 있어(jwt.access-token-validity-ms) 별도 파라미터가 필요 없다.
 * 리프레시 토큰은 member와 같은 공통 RefreshTokenRepository(Redis)에 저장한다.
 * 로그인은 최초 발급만 담당하고, Rotation은 별도 재발급 API에서 compareAndRotate()로 처리한다.
 *
 * Refresh Token은 DB와 Redis 두 저장소에 기록한다. DB 갱신은 AdminLoginTransactionService의 짧은
 * 트랜잭션에서 처리하고 Redis I/O는 트랜잭션 밖에서 수행한다. Redis 장애 시 DB 백업이 있으면
 * 로그인은 성공시키며, DB 장애 시에도 Redis 저장이 성공하면 로그인은 가능하다. 둘 다 저장하지
 * 못한 경우에만 로그인 실패로 처리한다. 이 구조는 feat/admin-logout의 로그인 트랜잭션 분리 방식과
 * 맞춰 두 브랜치 병합 시 충돌 범위를 줄인다.
 */
@Slf4j
@Service
public class AdminAuthService {

    // 실제 계정과 무관한 값이다. 계정이 없을 때도 이 해시로 BCrypt 를 돌려 응답 시간을 맞춘다 (SEC-6-04)
    private static final String DUMMY_PASSWORD_SOURCE = "dummy-password-for-constant-time-comparison";

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AdminLoginTransactionService adminLoginTransactionService;
    private final long refreshTokenValiditySeconds;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AdminAuthService(
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            AdminLoginTransactionService adminLoginTransactionService,
            Clock clock,
            @Value("${ADMIN_REFRESH_TOKEN_VALIDITY_SECONDS:86400}") long refreshTokenValiditySeconds) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.adminLoginTransactionService = adminLoginTransactionService;
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
            log.warn("event=ADMIN_LOGIN success=false loginId={}", maskLoginId(request.loginId()));
            throw new AdminException(AdminErrorCode.LOGIN_FAILED);
        }

        /*
         * 비활성 계정도 외부에는 일반 로그인 실패와 같은 응답으로 처리한다 (SEC-6-04).
         * 아이디/비밀번호가 맞더라도 계정 상태를 별도 코드로 알려주면 공격자가 계정 상태를 추측할 수 있으므로 LOGIN_FAILED 로 통일한다.
         */
        Admin admin = found.get();
        if (!admin.isActive()) {
            log.warn("event=ADMIN_LOGIN success=false loginId={}", maskLoginId(request.loginId()));
            throw new AdminException(AdminErrorCode.LOGIN_FAILED);
        }

        /*
         * Refresh Token 원문은 한 번만 만들고, 같은 해시를 DB 백업과 Redis에 사용한다.
         * DB 갱신은 별도 짧은 트랜잭션에서 먼저 시도한다. Redis가 장애여도 DB에 이 해시가
         * 남아 있으면 이후 재발급/로그아웃이 DB fallback으로 세션을 식별할 수 있다.
         */
        String rawRefreshToken = OpaqueTokenGenerator.generate();
        String refreshTokenHash = TokenHasher.sha256(rawRefreshToken);
        Duration refreshTtl = Duration.ofSeconds(refreshTokenValiditySeconds);
        LocalDateTime refreshTokenExpiresAt = LocalDateTime.now(clock).plus(refreshTtl);

        AdminLoginTransactionService.LoginDbState dbState = null;
        boolean dbSaved = false;
        try {
            dbState = adminLoginTransactionService.issueRefreshToken(
                    admin.getId(),
                    refreshTokenHash,
                    refreshTokenExpiresAt);
            dbSaved = true;
        } catch (DataAccessException | TransactionException e) {
            log.warn(
                    "event=ADMIN_LOGIN_DB_BACKUP_SAVE_FAILED adminId={} — Redis 저장으로 계속 진행",
                    admin.getId(),
                    e);
        }

        Long adminId = dbSaved ? dbState.adminId() : admin.getId();
        String loginId = dbSaved ? dbState.loginId() : admin.getLoginId();
        String name = dbSaved ? dbState.name() : admin.getName();
        var roleValue = dbSaved ? dbState.role() : admin.getRole();
        String role = roleValue.toAuthority();

        String accessToken = jwtTokenProvider.createAccessToken(
                adminId, TokenType.ADMIN, role);

        try {
            refreshTokenRepository.save(
                    rawRefreshToken,
                    adminId,
                    role,
                    TokenType.ADMIN,
                    false,
                    refreshTtl);
        } catch (DataAccessException e) {
            if (!dbSaved) {
                // 어느 저장소에도 RT 상태를 남기지 못했다면 발급한 RT는 곧바로 쓸 수 없으므로 로그인 실패가 맞다.
                log.error("event=ADMIN_LOGIN_REFRESH_TOKEN_SAVE_FAILED adminId={} — DB와 Redis 모두 저장 실패",
                        adminId, e);
                throw e;
            }

            // 핵심 fallback: Redis가 죽어 있어도 DB 백업이 있으므로 로그인 자체는 성공시킨다.
            log.warn("event=ADMIN_LOGIN_REDIS_SAVE_FAILED adminId={} — DB fallback으로 로그인 유지",
                    adminId, e);
        }

        AdminLoginResponse response = new AdminLoginResponse(
                jwtTokenProvider.getAccessTokenValidityMs() / 1000,
                new AdminLoginResponse.AdminSummary(
                        loginId, name, roleValue));

        log.info("event=ADMIN_LOGIN success=true adminId={} loginId={}",
                adminId, maskLoginId(loginId));

        // 두 토큰 원문은 응답 본문이 아니라 컨트롤러가 만드는 HttpOnly 쿠키로만 나간다
        return new AdminLoginResult(response, accessToken, rawRefreshToken, refreshTokenValiditySeconds);
    }

    private String maskLoginId(String loginId) { return PiiMasker.maskGeneric(loginId, 2, 1); }

}