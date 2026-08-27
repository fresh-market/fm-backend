package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.entity.AdminAuditLog;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.exception.AdminTokenErrorCode;
import com.freshmarket.admin.domain.repository.AdminAuditLogRepository;
import com.freshmarket.admin.domain.repository.AdminTokenRepository;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.opaque.TokenHasher;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository.RefreshTokenData;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository.RotateOutcome;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class AdminTokenServiceTest {

    private static final String JWT_SECRET = "test-jwt-secret-key-must-be-at-least-32-bytes-long";

    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private AdminTokenRepository adminTokenRepository;

    @Mock
    private AdminAuditLogRepository adminAuditLogRepository;

    private AdminTokenService sut;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(JWT_SECRET, 1_800_000L, 1_209_600_000L);
        sut = new AdminTokenService(
                jwtTokenProvider,
                refreshTokenRepository,
                adminTokenRepository,
                adminAuditLogRepository,
                Clock.systemDefaultZone(),
                86_400L);
    }

    private RefreshTokenData adminRefreshTokenData() {
        return new RefreshTokenData(1L, "ROLE_ADMIN", TokenType.ADMIN, false);
    }

    @Test
    void redis와_db_회전에_성공하면_새_access_refresh_token을_발급한다() {
        when(refreshTokenRepository.find("old-rt")).thenReturn(Optional.of(adminRefreshTokenData()));
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenReturn(RotateOutcome.success(
                        new RefreshTokenData(1L, "ROLE_ADMIN", TokenType.ADMIN, false)));
        when(adminTokenRepository.rotateKnownAdmin(eq(1L), anyString(), anyString(), any(), any()))
                .thenReturn(new AdminTokenRepository.RotationState(1L, AdminRole.ADMIN));

        AdminTokenService.ReissueResult result = sut.reissue("old-rt");

        assertThat(jwtTokenProvider.validateToken(result.accessToken())).isTrue();
        assertThat(jwtTokenProvider.getId(result.accessToken())).isEqualTo(1L);
        assertThat(jwtTokenProvider.getType(result.accessToken())).isEqualTo(TokenType.ADMIN);
        assertThat(result.refreshToken()).isNotBlank().isNotEqualTo("old-rt");
        assertThat(result.expiresInSeconds()).isEqualTo(1800L);
        assertThat(result.refreshTokenValiditySeconds()).isEqualTo(86400L);
    }

    @Test
    void redis에서_이미_회전된_token_재사용이_탐지되면_거부하고_실패_감사를_남긴다() {
        when(refreshTokenRepository.find("old-rt")).thenReturn(Optional.of(adminRefreshTokenData()));
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenReturn(RotateOutcome.reuseDetected(
                        new RefreshTokenData(1L, "ROLE_ADMIN", TokenType.ADMIN, false)));

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);

        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void redis_장애면_db_fallback으로_재발급한다() {
        when(refreshTokenRepository.find("old-rt")).thenReturn(Optional.of(adminRefreshTokenData()));
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        when(adminTokenRepository.rotateByHash(anyString(), anyString(), any(), any()))
                .thenReturn(new AdminTokenRepository.RotationState(1L, AdminRole.SUPER_ADMIN));

        AdminTokenService.ReissueResult result = sut.reissue("old-rt");

        assertThat(jwtTokenProvider.validateToken(result.accessToken())).isTrue();
        assertThat(jwtTokenProvider.getId(result.accessToken())).isEqualTo(1L);
        verify(refreshTokenRepository).save(
                eq(result.refreshToken()), eq(1L), eq("ROLE_SUPER_ADMIN"), eq(TokenType.ADMIN), eq(false), any());
    }

    @Test
    void db_fallback_성공후_redis_재저장도_실패해도_재발급은_성공한다() {
        when(refreshTokenRepository.find("old-rt")).thenReturn(Optional.of(adminRefreshTokenData()));
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        when(adminTokenRepository.rotateByHash(anyString(), anyString(), any(), any()))
                .thenReturn(new AdminTokenRepository.RotationState(1L, AdminRole.ADMIN));
        doThrow(new DataAccessResourceFailureException("still down"))
                .when(refreshTokenRepository).save(anyString(), eq(1L), anyString(), eq(TokenType.ADMIN), eq(false), any());

        AdminTokenService.ReissueResult result = sut.reissue("old-rt");

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
    }

    @Test
    void redis에_rt가_없어도_db_백업이_유효하면_db_fallback으로_재발급한다() {
        when(refreshTokenRepository.find("old-rt")).thenReturn(Optional.empty());
        when(adminTokenRepository.findAdminIdByRefreshTokenHash(anyString())).thenReturn(Optional.of(1L));
        when(adminTokenRepository.rotateByHash(anyString(), anyString(), any(), any()))
                .thenReturn(new AdminTokenRepository.RotationState(1L, AdminRole.ADMIN));

        AdminTokenService.ReissueResult result = sut.reissue("old-rt");

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        verify(refreshTokenRepository, never()).compareAndRotate(eq("old-rt"), anyString(), any());
        verify(adminTokenRepository).rotateByHash(anyString(), anyString(), any(), any());
    }

    @Test
    void db_fallback에서_관리자를_식별한_뒤_회전에_실패하면_실패_감사를_남긴다() {
        when(refreshTokenRepository.find("old-rt")).thenReturn(Optional.empty());
        when(adminTokenRepository.findAdminIdByRefreshTokenHash(anyString())).thenReturn(Optional.of(1L));
        when(adminTokenRepository.rotateByHash(anyString(), anyString(), any(), any()))
                .thenThrow(new AdminException(AdminTokenErrorCode.REFRESH_TOKEN_INVALID));

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);

        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void member_refresh_token이_관리자_엔드포인트로_들어오면_회전시키지_않고_거부한다() {
        when(refreshTokenRepository.find("old-rt"))
                .thenReturn(Optional.of(
                        new RefreshTokenData(1L, "ROLE_USER", TokenType.MEMBER, false)));

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);

        verify(refreshTokenRepository, never()).compareAndRotate(eq("old-rt"), anyString(), any());
    }

    @Test
    void redis_rotation_성공후_db_rotation이_실패하면_신규_redis_token을_정리하고_실패_감사를_남긴다() {
        when(refreshTokenRepository.find("old-rt")).thenReturn(Optional.of(adminRefreshTokenData()));
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenReturn(RotateOutcome.success(
                        new RefreshTokenData(1L, "ROLE_ADMIN", TokenType.ADMIN, false)));
        when(adminTokenRepository.rotateKnownAdmin(eq(1L), anyString(), anyString(), any(), any()))
                .thenThrow(new AdminException(AdminTokenErrorCode.REFRESH_TOKEN_INVALID));

        org.mockito.ArgumentCaptor<String> newTokenCaptor = org.mockito.ArgumentCaptor.forClass(String.class);

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);

        verify(refreshTokenRepository).compareAndRotate(eq("old-rt"), newTokenCaptor.capture(), any());
        verify(refreshTokenRepository).deleteByHash(TokenHasher.sha256(newTokenCaptor.getValue()));
        verify(refreshTokenRepository).deleteActiveKeyIfMatches(
                eq("ROLE_ADMIN"),
                eq(1L),
                eq(TokenHasher.sha256(newTokenCaptor.getValue())));
        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void redis_rotation_timeout이고_신규_token을_확인하지_못하면_db_fallback하지_않고_결과_미확정으로_실패한다() {
        RefreshTokenData adminData = new RefreshTokenData(1L, "ROLE_ADMIN", TokenType.ADMIN, false);
        when(refreshTokenRepository.find("old-rt")).thenReturn(Optional.of(adminData));
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenThrow(new QueryTimeoutException("redis timeout"));
        when(refreshTokenRepository.find(argThat(token -> !"old-rt".equals(token))))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminTokenErrorCode.REFRESH_TOKEN_RESULT_UNKNOWN);

        verify(adminTokenRepository, never()).rotateByHash(anyString(), anyString(), any(), any());
        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void redis_rotation_timeout이어도_신규_token이_확인되면_db를_확정하고_재발급한다() {
        RefreshTokenData adminData = new RefreshTokenData(1L, "ROLE_ADMIN", TokenType.ADMIN, false);
        when(refreshTokenRepository.find(anyString())).thenReturn(Optional.of(adminData));
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenThrow(new QueryTimeoutException("redis timeout"));
        when(adminTokenRepository.rotateKnownAdmin(eq(1L), anyString(), anyString(), any(), any()))
                .thenReturn(new AdminTokenRepository.RotationState(1L, AdminRole.ADMIN));

        AdminTokenService.ReissueResult result = sut.reissue("old-rt");

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        verify(adminTokenRepository, never()).rotateByHash(anyString(), anyString(), any(), any());
    }

    @Test
    void redis_rotation_timeout후_결과_확인도_실패하면_결과_미확정으로_실패한다() {
        RefreshTokenData adminData =
                new RefreshTokenData(
                        1L,
                        "ROLE_ADMIN",
                        TokenType.ADMIN,
                        false);

        when(refreshTokenRepository.find("old-rt"))
                .thenReturn(Optional.of(adminData));

        when(refreshTokenRepository.compareAndRotate(
                eq("old-rt"),
                anyString(),
                any()))
                .thenThrow(new QueryTimeoutException("redis timeout"));

        // timeout 이후 새 RT가 실제 생성됐는지 확인하려고 다시 Redis를 조회하지만,
        // 이 확인 작업 자체도 실패하는 상황
        when(refreshTokenRepository.find(
                argThat(token -> !"old-rt".equals(token))))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "redis confirmation failed"));

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AdminException.class)
                .extracting(e ->
                        ((AdminException) e).getErrorCode())
                .isEqualTo(AdminTokenErrorCode.REFRESH_TOKEN_RESULT_UNKNOWN);

        // 결과를 확정할 수 없으므로 DB fallback으로 넘어가면 안 된다.
        verify(adminTokenRepository, never()).rotateByHash(anyString(), anyString(), any(), any());
        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void 재발급_결과_toString은_토큰_원문을_마스킹한다() {
        AdminTokenService.ReissueResult result =
                new AdminTokenService.ReissueResult(
                        "secret-access-token",
                        1800L,
                        "secret-refresh-token",
                        86400L);

        String text = result.toString();

        assertThat(text)
                .contains("accessToken=****")
                .contains("refreshToken=****")
                .doesNotContain("secret-access-token")
                .doesNotContain("secret-refresh-token");
    }
}