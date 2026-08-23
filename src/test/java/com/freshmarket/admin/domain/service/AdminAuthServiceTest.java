package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
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

import java.time.*;
import java.util.Optional;

import com.freshmarket.common.auth.jwt.AccessTokenValidAfterRepository;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.opaque.TokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * DB/Redis 저장소처럼 외부 상태에 접근하는 Repository는 mock으로 격리한다.
 * PasswordEncoder와 JwtTokenProvider는 핵심 인증 로직을 실제로 검증하기 위해 실제 구현을 사용한다.
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

    @Test
    void 관리자_토큰_재발급에_성공하면_Access와_Refresh를_함께_회전한다() {
        Admin admin = AdminFixture.active("admin.kim", passwordEncoder.encode(RAW_PASSWORD), AdminRole.ADMIN);
        ReflectionTestUtils.setField(admin, "id", 1L);
        admin.issueRefreshToken("a".repeat(64), LocalDateTime.now(clock).plusDays(1));

        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.success(
                        new RefreshTokenRepository.RefreshTokenData(1L, "ROLE_ADMIN", TokenType.ADMIN, false)));
        when(adminRepository.findById(1L)).thenReturn(Optional.of(admin));

        AdminAuthService.ReissueResult result = adminAuthService.reissue("old-rt");

        assertThat(jwtTokenProvider.validateToken(result.accessToken())).isTrue();
        assertThat(jwtTokenProvider.getId(result.accessToken())).isEqualTo(1L);
        assertThat(jwtTokenProvider.getRole(result.accessToken())).isEqualTo("ROLE_ADMIN");
        assertThat(result.expiresInSeconds()).isEqualTo(1800L);
        assertThat(result.refreshToken()).isNotBlank().isNotEqualTo("old-rt");
        assertThat(result.refreshTokenValiditySeconds()).isEqualTo(86400L);
        assertThat(admin.getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(result.refreshToken()));
        assertThat(admin.getRefreshTokenExpiresAt()).isEqualTo(LocalDateTime.now(clock).plusDays(1));
        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void 전혀_모르는_관리자_리프레시_토큰이면_재발급을_거부한다() {
        when(refreshTokenRepository.compareAndRotate(eq("unknown-rt"), anyString(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.notFound());

        assertThatThrownBy(() -> adminAuthService.reissue("unknown-rt"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void 회원_리프레시_토큰은_관리자_재발급에_사용할_수_없다() {
        when(refreshTokenRepository.compareAndRotate(eq("member-rt"), anyString(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.success(
                        new RefreshTokenRepository.RefreshTokenData(7L, "ROLE_USER", TokenType.MEMBER, false)));

        assertThatThrownBy(() -> adminAuthService.reissue("member-rt"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void 이미_회전된_관리자_토큰이_재사용되면_현재_세션을_강제종료하고_거부한다() {
        Admin admin = AdminFixture.active("admin.kim", passwordEncoder.encode(RAW_PASSWORD), AdminRole.ADMIN);
        ReflectionTestUtils.setField(admin, "id", 1L);
        admin.issueRefreshToken("c".repeat(64), LocalDateTime.now(clock).plusDays(1));

        when(refreshTokenRepository.compareAndRotate(eq("reused-rt"), anyString(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.reuseDetected(
                        new RefreshTokenRepository.RefreshTokenData(1L, "ROLE_ADMIN", TokenType.ADMIN, false)));
        when(adminRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(refreshTokenRepository.findActiveHash("ROLE_ADMIN", 1L))
                .thenReturn(Optional.of("c".repeat(64)));

        assertThatThrownBy(() -> adminAuthService.reissue("reused-rt"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.REFRESH_TOKEN_INVALID);

        assertThat(admin.getRefreshTokenHash()).isNull();
        verify(refreshTokenRepository).deleteByHash("c".repeat(64));
        verify(accessTokenValidAfterRepository).invalidateBefore(
                eq("ROLE_ADMIN"), eq(1L), any(), any());
    }

    @Test
    void Redis_회전이_실패하면_DB_백업으로_재발급한다() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        Admin admin = AdminFixture.active("admin.kim", passwordEncoder.encode(RAW_PASSWORD), AdminRole.ADMIN);
        ReflectionTestUtils.setField(admin, "id", 1L);
        admin.issueRefreshToken(TokenHasher.sha256("old-rt"), LocalDateTime.now(clock).plusDays(1));
        when(adminRepository.findByRefreshTokenHash(TokenHasher.sha256("old-rt")))
                .thenReturn(Optional.of(admin));
        when(adminRepository.compareAndSetRefreshToken(eq(1L), eq(TokenHasher.sha256("old-rt")), anyString(), any()))
                .thenReturn(1);

        AdminAuthService.ReissueResult result = adminAuthService.reissue("old-rt");

        assertThat(jwtTokenProvider.validateToken(result.accessToken())).isTrue();
        assertThat(jwtTokenProvider.getId(result.accessToken())).isEqualTo(1L);
        assertThat(result.refreshToken()).isNotBlank().isNotEqualTo("old-rt");
        verify(refreshTokenRepository).save(
                eq(result.refreshToken()),
                eq(1L),
                eq("ROLE_ADMIN"),
                eq(TokenType.ADMIN),
                eq(false),
                eq(Duration.ofSeconds(ADMIN_REFRESH_TOKEN_VALIDITY_SECONDS)));
        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void Redis_장애시_DB에도_리프레시_토큰이_없으면_재발급을_거부한다() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        when(adminRepository.findByRefreshTokenHash(TokenHasher.sha256("old-rt")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminAuthService.reissue("old-rt"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void Redis_회전_성공후_관리자_계정이_없으면_재발급을_거부한다() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.success(
                        new RefreshTokenRepository.RefreshTokenData(999L, "ROLE_ADMIN", TokenType.ADMIN, false)));
        when(adminRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminAuthService.reissue("old-rt"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.REFRESH_TOKEN_INVALID);
    }

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