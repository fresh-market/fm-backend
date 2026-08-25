package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.dto.AdminLoginRequest;
import com.freshmarket.admin.domain.dto.AdminLoginResponse;
import com.freshmarket.admin.domain.dto.AdminLoginResult;
import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
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
    private final AdminLogoutTransactionService adminLogoutTransactionService;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AdminAuthService(
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            AccessTokenValidAfterRepository accessTokenValidAfterRepository,
            AdminLogoutTransactionService adminLogoutTransactionService,
            Clock clock,
            @Value("${ADMIN_REFRESH_TOKEN_VALIDITY_SECONDS:86400}") long refreshTokenValiditySeconds) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.accessTokenValidAfterRepository = accessTokenValidAfterRepository;
        this.adminLogoutTransactionService = adminLogoutTransactionService;
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
     * 관리자 로그아웃. DB의 Refresh Token 백업을 먼저 짧은 트랜잭션에서 폐기한 뒤
     * Redis 정리와 Access Token 차단은 트랜잭션 밖에서 수행한다.
     *
     * Refresh Token은 DB를 최종 판정 기준으로 사용한다. Redis 삭제가 실패하거나 결과가
     * 미확정이어도 DB에서 이미 폐기된 토큰은 후속 재발급에서 거부되어야 한다.
     * Access Token은 기존 공용 JwtAuthenticationFilter의 Redis fail-open 정책을 유지하되,
     * 이 로그아웃 요청 자체는 차단 커트라인 저장 결과를 확정하지 못하면 성공으로 응답하지 않는다.
     */
    public void logout(Long adminId, String role) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(role, "role");

        AdminLogoutTransactionService.LogoutDbState dbState =
                adminLogoutTransactionService.revokeRefreshToken(adminId);

        cleanupRefreshToken(role, adminId, dbState.refreshTokenHash());

        LocalDateTime cutoff = LocalDateTime.now(clock);
        invalidateAccessTokenOrThrow(role, adminId, cutoff);

        adminLogoutTransactionService.recordSuccess(adminId);
        log.info("event=ADMIN_LOGOUT success=true adminId={}", adminId);
    }

    private void cleanupRefreshToken(String role, Long adminId, String tokenHash) {
        if (tokenHash != null) {
            RedisMutationOutcome primaryOutcome = deleteRefreshTokenRecord(tokenHash);
            if (primaryOutcome != RedisMutationOutcome.CONFIRMED) {
                log.warn(
                        "event=ADMIN_REFRESH_TOKEN_RECORD_CLEANUP_{} role={} adminId={}",
                        primaryOutcome,
                        role,
                        adminId);
            }
        }

        RedisMutationOutcome activeKeyOutcome = deleteRefreshTokenActiveKey(role, adminId);
        if (activeKeyOutcome != RedisMutationOutcome.CONFIRMED) {
            log.warn(
                    "event=ADMIN_REFRESH_TOKEN_ACTIVE_KEY_CLEANUP_{} role={} adminId={}",
                    activeKeyOutcome,
                    role,
                    adminId);
        }
    }

    private RedisMutationOutcome deleteRefreshTokenRecord(String tokenHash) {
        try {
            refreshTokenRepository.deleteByHash(tokenHash);
            return RedisMutationOutcome.CONFIRMED;
        } catch (QueryTimeoutException | DataAccessResourceFailureException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_UNKNOWN target=record", e);
            return confirmOrRetryRefreshTokenRecordDeletion(tokenHash);
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_FAILED target=record", e);
            return RedisMutationOutcome.FAILED;
        }
    }

    private RedisMutationOutcome confirmOrRetryRefreshTokenRecordDeletion(String tokenHash) {
        Boolean deleted = isRefreshTokenRecordDeleted(tokenHash);
        if (Boolean.TRUE.equals(deleted)) {
            return RedisMutationOutcome.CONFIRMED;
        }

        try {
            refreshTokenRepository.deleteByHash(tokenHash);
        } catch (QueryTimeoutException | DataAccessResourceFailureException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_RETRY_UNKNOWN target=record", e);
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_RETRY_FAILED target=record", e);
            return RedisMutationOutcome.FAILED;
        }

        deleted = isRefreshTokenRecordDeleted(tokenHash);
        return Boolean.TRUE.equals(deleted)
                ? RedisMutationOutcome.CONFIRMED
                : RedisMutationOutcome.UNKNOWN;
    }

    private Boolean isRefreshTokenRecordDeleted(String tokenHash) {
        try {
            return !refreshTokenRepository.existsByHash(tokenHash);
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_CONFIRM_FAILED target=record", e);
            return null;
        }
    }

    private RedisMutationOutcome deleteRefreshTokenActiveKey(String role, Long adminId) {
        try {
            refreshTokenRepository.deleteActiveKey(role, adminId);
            return RedisMutationOutcome.CONFIRMED;
        } catch (QueryTimeoutException | DataAccessResourceFailureException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_UNKNOWN target=activeKey role={} adminId={}",
                    role, adminId, e);
            return confirmOrRetryRefreshTokenActiveKeyDeletion(role, adminId);
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_FAILED target=activeKey role={} adminId={}",
                    role, adminId, e);
            return RedisMutationOutcome.FAILED;
        }
    }

    private RedisMutationOutcome confirmOrRetryRefreshTokenActiveKeyDeletion(String role, Long adminId) {
        Boolean deleted = isRefreshTokenActiveKeyDeleted(role, adminId);
        if (Boolean.TRUE.equals(deleted)) {
            return RedisMutationOutcome.CONFIRMED;
        }

        try {
            refreshTokenRepository.deleteActiveKey(role, adminId);
        } catch (QueryTimeoutException | DataAccessResourceFailureException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_RETRY_UNKNOWN target=activeKey role={} adminId={}",
                    role, adminId, e);
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_RETRY_FAILED target=activeKey role={} adminId={}",
                    role, adminId, e);
            return RedisMutationOutcome.FAILED;
        }

        deleted = isRefreshTokenActiveKeyDeleted(role, adminId);
        return Boolean.TRUE.equals(deleted)
                ? RedisMutationOutcome.CONFIRMED
                : RedisMutationOutcome.UNKNOWN;
    }

    private Boolean isRefreshTokenActiveKeyDeleted(String role, Long adminId) {
        try {
            return refreshTokenRepository.findActiveHash(role, adminId).isEmpty();
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_REFRESH_TOKEN_DELETE_CONFIRM_FAILED target=activeKey role={} adminId={}",
                    role, adminId, e);
            return null;
        }
    }

    private void invalidateAccessTokenOrThrow(String role, Long adminId, LocalDateTime cutoff) {
        Duration ttl = Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs());

        try {
            accessTokenValidAfterRepository.invalidateBefore(role, adminId, cutoff, ttl);
            return;
        } catch (QueryTimeoutException | DataAccessResourceFailureException e) {
            log.warn("event=ADMIN_ACCESS_TOKEN_INVALIDATION_UNKNOWN role={} adminId={}", role, adminId, e);
        } catch (DataAccessException e) {
            throw logoutFailed(role, adminId, e);
        }

        if (isAccessTokenInvalidationConfirmed(role, adminId, cutoff)) {
            return;
        }

        try {
            accessTokenValidAfterRepository.invalidateBefore(role, adminId, cutoff, ttl);
            return;
        } catch (QueryTimeoutException | DataAccessResourceFailureException e) {
            log.warn("event=ADMIN_ACCESS_TOKEN_INVALIDATION_RETRY_UNKNOWN role={} adminId={}", role, adminId, e);
        } catch (DataAccessException e) {
            throw logoutFailed(role, adminId, e);
        }

        if (!isAccessTokenInvalidationConfirmed(role, adminId, cutoff)) {
            throw logoutFailed(role, adminId, null);
        }
    }

    private boolean isAccessTokenInvalidationConfirmed(String role, Long adminId, LocalDateTime cutoff) {
        try {
            // 저장된 커트라인이 cutoff 이상이면 cutoff 직전 토큰은 반드시 무효다.
            return !accessTokenValidAfterRepository.isValidAfter(
                    role, adminId, cutoff.minusNanos(1));
        } catch (DataAccessException e) {
            log.warn("event=ADMIN_ACCESS_TOKEN_INVALIDATION_CONFIRM_FAILED role={} adminId={}",
                    role, adminId, e);
            return false;
        }
    }

    private AdminException logoutFailed(String role, Long adminId, DataAccessException cause) {
        log.error("event=ADMIN_ACCESS_TOKEN_INVALIDATION_FAILED role={} adminId={}", role, adminId, cause);
        return cause == null
                ? new AdminException(AdminErrorCode.LOGOUT_FAILED)
                : new AdminException(AdminErrorCode.LOGOUT_FAILED, cause);
    }

    private enum RedisMutationOutcome {
        CONFIRMED,
        FAILED,
        UNKNOWN
    }

    private String maskLoginId(String loginId) {
        return PiiMasker.maskGeneric(loginId, 2, 1);
    }
}
