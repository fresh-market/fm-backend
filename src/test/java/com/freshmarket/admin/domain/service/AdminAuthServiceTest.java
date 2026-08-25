package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.dto.AdminLoginRequest;
import com.freshmarket.admin.domain.dto.AdminLoginResult;
import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminFixture;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminRepository;
import com.freshmarket.common.auth.jwt.AccessTokenValidAfterRepository;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;

import java.time.*;
import java.util.Optional;

import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.jwt.TokenType;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/*
 * AdminRepository 만 mock 이다 (들어오는 데이터를 제공하는 의존성, UT-4-01).
 * PasswordEncoder 와 JwtTokenProvider 는 순수 로직이라 실제 구현을 그대로 쓴다.
 * mock 으로 대체하면 "비밀번호가 실제로 검증되는가", "토큰이 실제로 만들어지는가" 를
 * 이 테스트가 더 이상 보장하지 못한다 (UT-1-01 회귀 방어).
 */
class AdminAuthServiceTest {

    private static final String RAW_PASSWORD = "Freahman!2026";
    private static final String TEST_JWT_SECRET =
            "test-only-secret-key-must-be-at-least-32-bytes-long-for-hmac-sha256";
    private static final long ACCESS_TOKEN_VALIDITY_MS = 1_800_000L;
    private static final long MEMBER_REFRESH_TOKEN_VALIDITY_MS = 1_209_600_000L;
    private static final long ADMIN_REFRESH_TOKEN_VALIDITY_SECONDS = 86_400L;

    private final AdminRepository adminRepository = mock(AdminRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
            TEST_JWT_SECRET, ACCESS_TOKEN_VALIDITY_MS, MEMBER_REFRESH_TOKEN_VALIDITY_MS);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository =
            mock(AccessTokenValidAfterRepository.class);
    private final AdminLogoutTransactionService adminLogoutTransactionService =
            mock(AdminLogoutTransactionService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-23T06:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final AdminAuthService adminAuthService = new AdminAuthService(
            adminRepository,
            passwordEncoder,
            jwtTokenProvider,
            refreshTokenRepository,
            accessTokenValidAfterRepository,
            adminLogoutTransactionService,
            clock,
            ADMIN_REFRESH_TOKEN_VALIDITY_SECONDS
    );

    @Test
    void 로그인_요청이_null이면_즉시_예외가_발생한다() {
        assertThatThrownBy(() -> adminAuthService.login(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("request");
    }

    @Test
    void 아이디와_비밀번호가_일치하면_토큰을_발급한다() {
        // given
        Admin admin = AdminFixture.active(
                "admin.kim",
                passwordEncoder.encode(RAW_PASSWORD),
                AdminRole.ADMIN
        );

        when(adminRepository.findByLoginId("admin.kim")).thenReturn(Optional.of(admin));
        when(adminRepository.findByIdForUpdate(admin.getId())).thenReturn(Optional.of(admin));

        AdminLoginRequest request = new AdminLoginRequest("admin.kim", RAW_PASSWORD);

        // when
        AdminLoginResult result = adminAuthService.login(request);

        // then
        assertThat(result.response().expiresInSeconds()).isEqualTo(1800L);
        assertThat(result.response().admin().loginId()).isEqualTo("admin.kim");
        assertThat(result.response().admin().role()).isEqualTo(AdminRole.ADMIN);

        // 토큰은 응답 본문이 아니라 컨트롤러가 HttpOnly 쿠키로 내려보낼 별도 값으로 전달된다.
        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.refreshTokenValiditySeconds()).isEqualTo(86400L);

        // 로그인에서 발급한 리프레시 토큰은 Redis 공통 저장소에 등록한다.
        // 재발급 시 이 레코드를 기준으로 Rotation(compareAndRotate)을 수행한다.
        verify(refreshTokenRepository).save(
                result.refreshToken(),
                admin.getId(),
                "ROLE_ADMIN",
                TokenType.ADMIN,
                false,
                Duration.ofSeconds(ADMIN_REFRESH_TOKEN_VALIDITY_SECONDS));

        // Redis active key 유실 시 로그아웃이 사용할 DB 백업도 함께 남긴다.
        assertThat(admin.getRefreshTokenHash()).isNotBlank();
        assertThat(admin.getRefreshTokenExpiresAt())
                .isEqualTo(LocalDateTime.now(clock).plusDays(1));
    }

    @Test
    void 존재하지_않는_아이디면_로그인에_실패한다() {
        // given
        when(adminRepository.findByLoginId("nobody")).thenReturn(Optional.empty());

        AdminLoginRequest request = new AdminLoginRequest("nobody", RAW_PASSWORD);

        // when, then
        // 이 경로에서도 내부적으로 더미 해시로 BCrypt 를 돌린다 (SEC-6-04).
        // 예외 없이 LOGIN_FAILED 로 끝나는 것 자체가 더미 해시가
        // 유효한 BCrypt 형식이라는 회귀 방어다.
        assertThatThrownBy(() -> adminAuthService.login(request))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGIN_FAILED);
    }

    @Test
    void 비밀번호가_틀리면_로그인에_실패한다() {
        // given
        Admin admin = AdminFixture.active(
                "admin.kim",
                passwordEncoder.encode(RAW_PASSWORD),
                AdminRole.ADMIN
        );

        when(adminRepository.findByLoginId("admin.kim"))
                .thenReturn(Optional.of(admin));

        AdminLoginRequest request =
                new AdminLoginRequest("admin.kim", "wrong-password");

        // when, then
        assertThatThrownBy(() -> adminAuthService.login(request))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGIN_FAILED);
    }

    /*
     * 비활성 계정도 외부에는 일반 로그인 실패와 같은 응답을 반환한다 (SEC-6-04).
     * 올바른 비밀번호를 입력하더라도 계정 상태를 별도 오류 코드로 노출하지 않는다.
     */
    @Test
    void 비활성_계정이면_비밀번호가_맞아도_일반_로그인_실패로_응답한다() {
        // given
        Admin admin = AdminFixture.inactive(
                "admin.kim",
                passwordEncoder.encode(RAW_PASSWORD),
                AdminRole.ADMIN
        );

        when(adminRepository.findByLoginId("admin.kim"))
                .thenReturn(Optional.of(admin));

        AdminLoginRequest request =
                new AdminLoginRequest("admin.kim", RAW_PASSWORD);

        // when, then
        assertThatThrownBy(() -> adminAuthService.login(request))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGIN_FAILED);
    }

    // 비활성 계정은 비밀번호 일치 여부와 관계없이 외부에 계정 상태를 노출하지 않는다 (SEC-6-04).
    @Test
    void 비활성_계정이어도_비밀번호가_틀리면_계정_상태를_알려주지_않는다() {
        // given
        Admin admin = AdminFixture.inactive(
                "admin.kim",
                passwordEncoder.encode(RAW_PASSWORD),
                AdminRole.ADMIN
        );

        when(adminRepository.findByLoginId("admin.kim"))
                .thenReturn(Optional.of(admin));

        AdminLoginRequest request =
                new AdminLoginRequest("admin.kim", "wrong-password");

        // when, then
        assertThatThrownBy(() -> adminAuthService.login(request))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGIN_FAILED);
    }

    /*
     * ==========================
     * 관리자 로그아웃 테스트
     * ==========================
     */

    @Test
    void 존재하지_않는_관리자로_로그아웃하면_로그인_실패_예외가_발생한다() {
        when(adminLogoutTransactionService.revokeRefreshToken(999L))
                .thenThrow(new AdminException(AdminErrorCode.LOGIN_FAILED));

        assertThatThrownBy(() -> adminAuthService.logout(999L, "ROLE_ADMIN"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGIN_FAILED);
    }

    @Test
    void 로그아웃하면_DB_폐기후_Redis와_액세스토큰을_정리하고_감사로그를_기록한다() {
        String tokenHash = "a".repeat(64);
        when(adminLogoutTransactionService.revokeRefreshToken(1L))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(tokenHash));

        adminAuthService.logout(1L, "ROLE_ADMIN");

        verify(adminLogoutTransactionService).revokeRefreshToken(1L);
        verify(refreshTokenRepository).deleteByHash(tokenHash);
        verify(refreshTokenRepository).deleteActiveKey("ROLE_ADMIN", 1L);
        verify(accessTokenValidAfterRepository).invalidateBefore(
                "ROLE_ADMIN",
                1L,
                LocalDateTime.now(clock),
                Duration.ofMillis(ACCESS_TOKEN_VALIDITY_MS));
        verify(adminLogoutTransactionService).recordSuccess(1L);
    }

    @Test
    void DB에_리프레시토큰_해시가_없어도_Redis_active_key는_정리한다() {
        when(adminLogoutTransactionService.revokeRefreshToken(1L))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(null));

        adminAuthService.logout(1L, "ROLE_ADMIN");

        verify(refreshTokenRepository).deleteActiveKey("ROLE_ADMIN", 1L);
        verify(adminLogoutTransactionService).recordSuccess(1L);
    }

    @Test
    void RefreshToken_레코드_삭제가_타임아웃이면_후속조회로_삭제를_확정한다() {
        String tokenHash = "a".repeat(64);
        when(adminLogoutTransactionService.revokeRefreshToken(1L))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(tokenHash));
        doThrow(new QueryTimeoutException("redis timeout"))
                .when(refreshTokenRepository).deleteByHash(tokenHash);
        when(refreshTokenRepository.existsByHash(tokenHash)).thenReturn(false);

        adminAuthService.logout(1L, "ROLE_ADMIN");

        verify(refreshTokenRepository).existsByHash(tokenHash);
        verify(accessTokenValidAfterRepository).invalidateBefore(
                "ROLE_ADMIN",
                1L,
                LocalDateTime.now(clock),
                Duration.ofMillis(ACCESS_TOKEN_VALIDITY_MS));
        verify(adminLogoutTransactionService).recordSuccess(1L);
    }

    @Test
    void RefreshToken_active_key_삭제가_연결실패면_후속조회로_삭제를_확정한다() {
        when(adminLogoutTransactionService.revokeRefreshToken(1L))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(null));
        doThrow(new DataAccessResourceFailureException("redis disconnected"))
                .when(refreshTokenRepository).deleteActiveKey("ROLE_ADMIN", 1L);
        when(refreshTokenRepository.findActiveHash("ROLE_ADMIN", 1L))
                .thenReturn(Optional.empty());

        adminAuthService.logout(1L, "ROLE_ADMIN");

        verify(refreshTokenRepository).findActiveHash("ROLE_ADMIN", 1L);
        verify(adminLogoutTransactionService).recordSuccess(1L);
    }

    @Test
    void RefreshToken_Redis_정리가_일반실패여도_DB가_최종기준이므로_액세스토큰_차단은_계속한다() {
        String tokenHash = "a".repeat(64);
        when(adminLogoutTransactionService.revokeRefreshToken(1L))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(tokenHash));
        doThrow(new DataAccessResourceFailureException("redis down"))
                .when(refreshTokenRepository).deleteByHash(tokenHash);
        when(refreshTokenRepository.existsByHash(tokenHash))
                .thenThrow(new DataAccessResourceFailureException("redis still down"));

        adminAuthService.logout(1L, "ROLE_ADMIN");

        verify(accessTokenValidAfterRepository).invalidateBefore(
                "ROLE_ADMIN",
                1L,
                LocalDateTime.now(clock),
                Duration.ofMillis(ACCESS_TOKEN_VALIDITY_MS));
        verify(adminLogoutTransactionService).recordSuccess(1L);
    }

    @Test
    void AccessToken_차단_타임아웃이어도_후속조회에서_반영이_확인되면_로그아웃에_성공한다() {
        LocalDateTime cutoff = LocalDateTime.now(clock);
        when(adminLogoutTransactionService.revokeRefreshToken(1L))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(null));
        doThrow(new QueryTimeoutException("redis timeout"))
                .when(accessTokenValidAfterRepository).invalidateBefore(
                        "ROLE_ADMIN",
                        1L,
                        cutoff,
                        Duration.ofMillis(ACCESS_TOKEN_VALIDITY_MS));
        when(accessTokenValidAfterRepository.isValidAfter(
                "ROLE_ADMIN", 1L, cutoff.minusNanos(1)))
                .thenReturn(false);

        adminAuthService.logout(1L, "ROLE_ADMIN");

        verify(accessTokenValidAfterRepository).isValidAfter(
                "ROLE_ADMIN", 1L, cutoff.minusNanos(1));
        verify(adminLogoutTransactionService).recordSuccess(1L);
    }

    @Test
    void AccessToken_차단_결과를_끝까지_확정하지_못하면_로그아웃_성공으로_처리하지_않는다() {
        LocalDateTime cutoff = LocalDateTime.now(clock);
        when(adminLogoutTransactionService.revokeRefreshToken(1L))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(null));
        doThrow(new QueryTimeoutException("redis timeout"))
                .when(accessTokenValidAfterRepository).invalidateBefore(
                        "ROLE_ADMIN",
                        1L,
                        cutoff,
                        Duration.ofMillis(ACCESS_TOKEN_VALIDITY_MS));
        when(accessTokenValidAfterRepository.isValidAfter(
                "ROLE_ADMIN", 1L, cutoff.minusNanos(1)))
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        assertThatThrownBy(() -> adminAuthService.logout(1L, "ROLE_ADMIN"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGOUT_FAILED);
    }

    @Test
    void 비밀번호_검증후_잠금_조회에서_관리자가_없으면_로그인에_실패한다() {
        // given
        Admin admin = AdminFixture.active(
                "admin.kim",
                passwordEncoder.encode(RAW_PASSWORD),
                AdminRole.ADMIN
        );

        when(adminRepository.findByLoginId("admin.kim"))
                .thenReturn(Optional.of(admin));

        when(adminRepository.findByIdForUpdate(admin.getId()))
                .thenReturn(Optional.empty());

        AdminLoginRequest request =
                new AdminLoginRequest("admin.kim", RAW_PASSWORD);

        // when, then
        assertThatThrownBy(() -> adminAuthService.login(request))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGIN_FAILED);
    }

    @Test
    void 비밀번호_검증후_잠금_조회에서_관리자가_비활성화됐으면_로그인에_실패한다() {
        // given
        Admin admin = AdminFixture.active(
                "admin.kim",
                passwordEncoder.encode(RAW_PASSWORD),
                AdminRole.ADMIN
        );

        Admin lockedAdmin = AdminFixture.inactive(
                "admin.kim",
                passwordEncoder.encode(RAW_PASSWORD),
                AdminRole.ADMIN
        );

        when(adminRepository.findByLoginId("admin.kim"))
                .thenReturn(Optional.of(admin));

        when(adminRepository.findByIdForUpdate(admin.getId()))
                .thenReturn(Optional.of(lockedAdmin));

        AdminLoginRequest request =
                new AdminLoginRequest("admin.kim", RAW_PASSWORD);

        // when, then
        assertThatThrownBy(() -> adminAuthService.login(request))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGIN_FAILED);
    }

    /*
     * ==========================
     * 기존 로그인 JWT 검증 테스트
     * ==========================
     */

    @Test
    void 관리자로_로그인하면_토큰의_role_클레임이_ROLE_ADMIN_이다() {
        // given
        Admin admin = AdminFixture.active("admin.kim", passwordEncoder.encode(RAW_PASSWORD), AdminRole.ADMIN);
        when(adminRepository.findByLoginId("admin.kim")).thenReturn(Optional.of(admin));
        when(adminRepository.findByIdForUpdate(admin.getId())).thenReturn(Optional.of(admin));
        AdminLoginRequest request = new AdminLoginRequest("admin.kim", RAW_PASSWORD);

        // when
        AdminLoginResult result = adminAuthService.login(request);

        // then
        assertThat(jwtTokenProvider.getRole(result.accessToken())).isEqualTo("ROLE_ADMIN");
    }
}