package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.dto.AdminLoginRequest;
import com.freshmarket.admin.domain.dto.AdminLoginResult;
import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminAuditLog;
import com.freshmarket.admin.domain.entity.AdminFixture;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminAuditLogRepository;
import com.freshmarket.admin.domain.repository.AdminRepository;
import com.freshmarket.common.auth.jwt.AccessTokenValidAfterRepository;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;

import java.time.*;
import java.util.Optional;

import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.jwt.TokenType;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

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
    private final AdminAuditLogRepository adminAuditLogRepository = mock(AdminAuditLogRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-23T06:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final AdminAuthService adminAuthService = new AdminAuthService(
            adminRepository,
            passwordEncoder,
            jwtTokenProvider,
            refreshTokenRepository,
            accessTokenValidAfterRepository,
            adminAuditLogRepository,
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
        // given
        when(adminRepository.findById(999L))
                .thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> adminAuthService.logout(999L, "ROLE_ADMIN"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGIN_FAILED);
    }

    @Test
    void 로그아웃하면_리프레시토큰과_액세스토큰을_함께_무효화한다() {
        Admin admin = AdminFixture.active("admin.kim", passwordEncoder.encode(RAW_PASSWORD), AdminRole.ADMIN);
        ReflectionTestUtils.setField(admin, "id", 1L);
        admin.issueRefreshToken("a".repeat(64), LocalDateTime.now(clock).plusDays(1));

        when(adminRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(refreshTokenRepository.findActiveHash("ROLE_ADMIN", 1L))
                .thenReturn(Optional.of("a".repeat(64)));

        adminAuthService.logout(1L, "ROLE_ADMIN");

        assertThat(admin.getRefreshTokenHash()).isNull();
        assertThat(admin.getRefreshTokenExpiresAt()).isNull();
        assertThat(admin.isActive()).isTrue();
        verify(refreshTokenRepository).deleteByHash("a".repeat(64));
        verify(refreshTokenRepository).deleteActiveKey("ROLE_ADMIN", 1L);
        verify(accessTokenValidAfterRepository).invalidateBefore(
                "ROLE_ADMIN",
                1L,
                LocalDateTime.now(clock),
                Duration.ofMillis(ACCESS_TOKEN_VALIDITY_MS));
        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void 로그아웃시_Redis_보조인덱스가_없으면_DB_해시로_폴백한다() {
        Admin admin = AdminFixture.active("admin.kim", passwordEncoder.encode(RAW_PASSWORD), AdminRole.ADMIN);
        ReflectionTestUtils.setField(admin, "id", 1L);
        admin.issueRefreshToken("b".repeat(64), LocalDateTime.now(clock).plusDays(1));

        when(adminRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(refreshTokenRepository.findActiveHash("ROLE_ADMIN", 1L)).thenReturn(Optional.empty());

        adminAuthService.logout(1L, "ROLE_ADMIN");

        verify(refreshTokenRepository).deleteByHash("b".repeat(64));
        assertThat(admin.getRefreshTokenHash()).isNull();
    }

    @Test
    void 로그아웃시_Redis_조회에_실패해도_DB_리프레시토큰은_폐기한다() {
        Admin admin = AdminFixture.active(
                "admin.kim",
                passwordEncoder.encode(RAW_PASSWORD),
                AdminRole.ADMIN);

        ReflectionTestUtils.setField(admin, "id", 1L);
        admin.issueRefreshToken(
                "a".repeat(64),
                LocalDateTime.now(clock).plusDays(1));

        when(adminRepository.findById(1L))
                .thenReturn(Optional.of(admin));

        when(refreshTokenRepository.findActiveHash("ROLE_ADMIN", 1L))
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        adminAuthService.logout(1L, "ROLE_ADMIN");

        assertThat(admin.getRefreshTokenHash()).isNull();
        assertThat(admin.getRefreshTokenExpiresAt()).isNull();
        assertThat(admin.isActive()).isTrue();

        verify(accessTokenValidAfterRepository).invalidateBefore(
                "ROLE_ADMIN",
                1L,
                LocalDateTime.now(clock),
                Duration.ofMillis(ACCESS_TOKEN_VALIDITY_MS));

        verify(adminAuditLogRepository)
                .save(any(AdminAuditLog.class));
    }

    @Test
    void 로그아웃시_토큰_해시가_없어도_active_key_삭제를_시도한다() {
        // given
        Admin admin = AdminFixture.active(
                "admin.kim",
                passwordEncoder.encode(RAW_PASSWORD),
                AdminRole.ADMIN);

        ReflectionTestUtils.setField(
                admin,
                "id",
                1L);

        when(adminRepository.findById(1L))
                .thenReturn(Optional.of(admin));

        /*
         * Redis active key 조회 결과도 없고,
         * Admin 엔티티에도 Refresh Token 해시가 없는 상황이다.
         */
        when(refreshTokenRepository.findActiveHash(
                "ROLE_ADMIN",
                1L))
                .thenReturn(Optional.empty());

        // when
        adminAuthService.logout(
                1L,
                "ROLE_ADMIN");

        // then
        /*
         * 삭제할 실제 Refresh Token 해시가 없어도
         * 관리자별 active key 자체는 정리해야 한다.
         */
        verify(refreshTokenRepository)
                .deleteActiveKey(
                        "ROLE_ADMIN",
                        1L);
    }

    @Test
    void 로그아웃시_Redis_삭제에_실패해도_처리를_계속한다() {
        // given
        Admin admin = AdminFixture.active(
                "admin.kim",
                passwordEncoder.encode(RAW_PASSWORD),
                AdminRole.ADMIN);

        ReflectionTestUtils.setField(
                admin,
                "id",
                1L);

        admin.issueRefreshToken(
                "a".repeat(64),
                LocalDateTime.now(clock)
                        .plusDays(1));

        when(adminRepository.findById(1L))
                .thenReturn(Optional.of(admin));

        when(refreshTokenRepository.findActiveHash(
                "ROLE_ADMIN",
                1L))
                .thenReturn(
                        Optional.of(
                                "a".repeat(64)));

        doThrow(
                new DataAccessResourceFailureException(
                        "redis delete failed"))
                .when(refreshTokenRepository)
                .deleteByHash(
                        "a".repeat(64));

        // when
        adminAuthService.logout(
                1L,
                "ROLE_ADMIN");

        // then
        /*
         * Redis 삭제가 실패해도 DB에 저장된
         * Refresh Token 백업은 이미 폐기되어 있어야 한다.
         */
        assertThat(admin.getRefreshTokenHash())
                .isNull();

        assertThat(admin.getRefreshTokenExpiresAt())
                .isNull();

        /*
         * Redis Refresh Token 삭제 실패로
         * 로그아웃 전체를 중단하지 않고
         * Access Token 무효화까지 계속 진행해야 한다.
         */
        verify(accessTokenValidAfterRepository)
                .invalidateBefore(
                        "ROLE_ADMIN",
                        1L,
                        LocalDateTime.now(clock),
                        Duration.ofMillis(
                                ACCESS_TOKEN_VALIDITY_MS));

        /*
         * 감사 로그 기록도 계속 수행한다.
         */
        verify(adminAuditLogRepository)
                .save(any(AdminAuditLog.class));
    }

    @Test
    void 로그아웃시_AccessToken_커트라인_기록에_실패하면_예외를_전달한다() {
        // given
        Admin admin = AdminFixture.active(
                "admin.kim",
                passwordEncoder.encode(RAW_PASSWORD),
                AdminRole.ADMIN);

        ReflectionTestUtils.setField(
                admin,
                "id",
                1L);

        when(adminRepository.findById(1L))
                .thenReturn(Optional.of(admin));

        when(refreshTokenRepository.findActiveHash(
                "ROLE_ADMIN",
                1L))
                .thenReturn(Optional.empty());

        doThrow(
                new DataAccessResourceFailureException(
                        "redis down"))
                .when(accessTokenValidAfterRepository)
                .invalidateBefore(
                        "ROLE_ADMIN",
                        1L,
                        LocalDateTime.now(clock),
                        Duration.ofMillis(
                                ACCESS_TOKEN_VALIDITY_MS));

        // when & then
        assertThatThrownBy(() -> adminAuthService.logout(1L, "ROLE_ADMIN"))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage("redis down");
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
        AdminLoginRequest request = new AdminLoginRequest("admin.kim", RAW_PASSWORD);

        // when
        AdminLoginResult result = adminAuthService.login(request);

        // then
        assertThat(jwtTokenProvider.getRole(result.accessToken())).isEqualTo("ROLE_ADMIN");
    }
}