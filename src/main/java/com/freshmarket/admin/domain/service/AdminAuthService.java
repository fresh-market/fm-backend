package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.dto.AdminLoginRequest;
import com.freshmarket.admin.domain.dto.AdminLoginResponse;
import com.freshmarket.admin.domain.dto.AdminLoginResult;
import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminAuditLog;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminAuditLogRepository;
import com.freshmarket.admin.domain.repository.AdminRepository;
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
    private final long refreshTokenValiditySeconds;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AdminAuthService(
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            AccessTokenValidAfterRepository accessTokenValidAfterRepository,
            AdminAuditLogRepository adminAuditLogRepository,
            Clock clock,
            @Value("${ADMIN_REFRESH_TOKEN_VALIDITY_SECONDS:86400}") long refreshTokenValiditySeconds) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.accessTokenValidAfterRepository = accessTokenValidAfterRepository;
        this.adminAuditLogRepository = adminAuditLogRepository;
        this.clock = clock;
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

        String accessToken = jwtTokenProvider.createAccessToken(
                admin.getId(), TokenType.ADMIN, admin.getRole().toAuthority());

        String rawRefreshToken = OpaqueTokenGenerator.generate();
        Duration refreshTtl = Duration.ofSeconds(refreshTokenValiditySeconds);
        refreshTokenRepository.save(
                rawRefreshToken,
                admin.getId(),
                admin.getRole().toAuthority(),
                TokenType.ADMIN,
                false,
                refreshTtl);

        /* Redis의 active key가 유실돼도 로그아웃 시 실제 Refresh Token 레코드를 찾을 수 있도록 DB에도 해시와 만료시각을 백업한다.
         * 평문 토큰은 DB에 저장하지 않는다.
         */
        admin.issueRefreshToken(
                TokenHasher.sha256(rawRefreshToken),
                LocalDateTime.now(clock).plus(refreshTtl));

        AdminLoginResponse response = new AdminLoginResponse(
                jwtTokenProvider.getAccessTokenValidityMs() / 1000,
                new AdminLoginResponse.AdminSummary(
                        admin.getLoginId(), admin.getName(), admin.getRole()));

        log.info("event=ADMIN_LOGIN success=true adminId={} loginId={}",
                admin.getId(), maskLoginId(admin.getLoginId()));

        // 두 토큰 원문은 응답 본문이 아니라 컨트롤러가 만드는 HttpOnly 쿠키로만 나간다
        return new AdminLoginResult(response, accessToken, rawRefreshToken, refreshTokenValiditySeconds);
    }

    /*
     * 관리자 로그아웃. Refresh Token은 Redis와 DB 백업에서 폐기하고, Access Token은
     * 계정 단위 valid-after 커트라인을 기록해 이미 발급된 토큰까지 즉시 무효화한다.
     */
    @Transactional(timeout = 5)
    public void logout(Long adminId, String role) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(role, "role");

        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new AdminException(AdminErrorCode.LOGIN_FAILED));

        Optional<String> tokenHash;
        try {
            tokenHash = refreshTokenRepository.findActiveHash(role, adminId);
            if (tokenHash.isEmpty()) {
                tokenHash = Optional.ofNullable(admin.getRefreshTokenHash());
                if (tokenHash.isPresent()) {
                    log.warn("event=ADMIN_ACTIVE_KEY_MISSING_DB_FALLBACK_USED role={} adminId={}", role, adminId);
                }
            }
        } catch (DataAccessException e) {
            log.warn(
                    "event=ADMIN_REDIS_LOOKUP_FAILED role={} adminId={}",
                    role,
                    adminId,
                    e);

            tokenHash = Optional.empty();
        }

        // DB 백업은 Redis 삭제 성공 여부와 무관하게 먼저 폐기한다.
        admin.revokeRefreshToken();

        try {
            tokenHash.ifPresent(refreshTokenRepository::deleteByHash);
            refreshTokenRepository.deleteActiveKey(role, adminId);
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REDIS_DELETE_FAILED role={} adminId={} — DB 백업 삭제만 반영", role, adminId, e);
        }

        try {
            accessTokenValidAfterRepository.invalidateBefore(
                    role,
                    adminId,
                    LocalDateTime.now(clock),
                    Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));
        } catch (DataAccessException e) {
            // 공용 JwtAuthenticationFilter도 Redis 장애 시 fail-open 정책을 사용한다.
            // 커트라인 저장 실패 하나 때문에 로그아웃 전체를 500으로 만들지 않고 같은 정책을 따른다.
            log.warn("event=ADMIN_INVALIDATE_BEFORE_FAILED role={} adminId={}", role, adminId, e);
        }

        adminAuditLogRepository.save(
                AdminAuditLog.of(adminId, "ADMIN_LOGOUT", String.valueOf(adminId), null));
        log.info("event=ADMIN_LOGOUT success=true adminId={}", adminId);
    }

    private String maskLoginId(String loginId) {
        return PiiMasker.maskGeneric(loginId, 2, 1);
    }
}
