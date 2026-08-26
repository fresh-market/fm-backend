package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.exception.AdminTokenErrorCode;
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

@ExtendWith(MockitoExtension.class)
class AdminTokenServiceTest {

    private static final String JWT_SECRET = "test-jwt-secret-key-must-be-at-least-32-bytes-long";

    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private AdminTokenTransactionService transactionService;

    private AdminTokenService sut;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(JWT_SECRET, 1_800_000L, 1_209_600_000L);
        sut = new AdminTokenService(
                jwtTokenProvider,
                refreshTokenRepository,
                transactionService,
                Clock.systemDefaultZone(),
                86_400L);
    }

    @Test
    void redis와_db_회전에_성공하면_새_access_refresh_token을_발급한다() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenReturn(RotateOutcome.success(
                        new RefreshTokenData(1L, "ROLE_ADMIN", TokenType.ADMIN, false)));
        when(transactionService.rotateKnownAdmin(eq(1L), anyString(), anyString(), any(), any()))
                .thenReturn(new AdminTokenTransactionService.RotationState(1L, AdminRole.ADMIN));

        AdminTokenService.ReissueResult result = sut.reissue("old-rt");

        assertThat(jwtTokenProvider.validateToken(result.accessToken())).isTrue();
        assertThat(jwtTokenProvider.getId(result.accessToken())).isEqualTo(1L);
        assertThat(jwtTokenProvider.getType(result.accessToken())).isEqualTo(TokenType.ADMIN);
        assertThat(result.refreshToken()).isNotBlank().isNotEqualTo("old-rt");
        assertThat(result.expiresInSeconds()).isEqualTo(1800L);
        assertThat(result.refreshTokenValiditySeconds()).isEqualTo(86400L);
    }

    @Test
    void redis에서_이미_회전된_token_재사용이_탐지되면_거부한다() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenReturn(RotateOutcome.reuseDetected(
                        new RefreshTokenData(1L, "ROLE_ADMIN", TokenType.ADMIN, false)));

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void redis_장애면_db_fallback으로_재발급한다() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        when(transactionService.rotateByHash(anyString(), anyString(), any(), any()))
                .thenReturn(new AdminTokenTransactionService.RotationState(1L, AdminRole.SUPER_ADMIN));

        AdminTokenService.ReissueResult result = sut.reissue("old-rt");

        assertThat(jwtTokenProvider.validateToken(result.accessToken())).isTrue();
        assertThat(jwtTokenProvider.getId(result.accessToken())).isEqualTo(1L);
        verify(refreshTokenRepository).save(
                eq(result.refreshToken()), eq(1L), eq("ROLE_SUPER_ADMIN"), eq(TokenType.ADMIN), eq(false), any());
    }

    @Test
    void db_fallback_성공후_redis_재저장도_실패해도_재발급은_성공한다() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        when(transactionService.rotateByHash(anyString(), anyString(), any(), any()))
                .thenReturn(new AdminTokenTransactionService.RotationState(1L, AdminRole.ADMIN));
        doThrow(new DataAccessResourceFailureException("still down"))
                .when(refreshTokenRepository).save(anyString(), eq(1L), anyString(), eq(TokenType.ADMIN), eq(false), any());

        AdminTokenService.ReissueResult result = sut.reissue("old-rt");

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
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
    void redis_rotation_성공후_db_rotation이_실패하면_신규_redis_token을_정리한다() {
        when(refreshTokenRepository.compareAndRotate(eq("old-rt"), anyString(), any()))
                .thenReturn(RotateOutcome.success(
                        new RefreshTokenData(1L, "ROLE_ADMIN", TokenType.ADMIN, false)));
        when(transactionService.rotateKnownAdmin(eq(1L), anyString(), anyString(), any(), any()))
                .thenThrow(new AdminException(AdminTokenErrorCode.REFRESH_TOKEN_INVALID));

        org.mockito.ArgumentCaptor<String> newTokenCaptor = org.mockito.ArgumentCaptor.forClass(String.class);

        assertThatThrownBy(() -> sut.reissue("old-rt"))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);

        verify(refreshTokenRepository).compareAndRotate(eq("old-rt"), newTokenCaptor.capture(), any());
        verify(refreshTokenRepository).deleteByHash(TokenHasher.sha256(newTokenCaptor.getValue()));
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