package com.freshmarket.admin.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/*
 * DB와 Redis 정리를 서버에서 각각 한 번만 수행하는 정책을 검증한다.
 * 저장소가 실패해도 같은 요청 안에서 재시도하지 않는다.
 */
class AdminRefreshTokenCleanupServiceTest {

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final AdminLogoutTransactionService adminLogoutTransactionService =
            mock(AdminLogoutTransactionService.class);

    private final AdminRefreshTokenCleanupService sut =
            new AdminRefreshTokenCleanupService(
                    refreshTokenRepository,
                    adminLogoutTransactionService);

    @Test
    void DB_폐기가_성공하면_결과를_바로_반환한다() {
        String tokenHash = "a".repeat(64);
        when(adminLogoutTransactionService.revokeRefreshToken(1L))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(tokenHash));

        AdminLogoutTransactionService.LogoutDbState result = sut.revokeDbOnce(1L);

        assertThat(result.refreshTokenHash()).isEqualTo(tokenHash);
        verify(adminLogoutTransactionService, times(1)).revokeRefreshToken(1L);
    }

    @Test
    void DB_폐기가_실패하면_재시도하지_않고_null을_반환한다() {
        when(adminLogoutTransactionService.revokeRefreshToken(1L))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        AdminLogoutTransactionService.LogoutDbState result = sut.revokeDbOnce(1L);

        assertThat(result).isNull();
        verify(adminLogoutTransactionService, times(1)).revokeRefreshToken(1L);
    }

    @Test
    void Redis_정리가_성공하면_true를_반환한다() {
        String tokenHash = "a".repeat(64);

        boolean result = sut.cleanupRedisOnce("ROLE_ADMIN", 1L, tokenHash);

        assertThat(result).isTrue();
        verify(refreshTokenRepository, times(1))
                .revokeIfActiveHashMatches(tokenHash, "ROLE_ADMIN", 1L);
    }

    @Test
    void Redis_정리가_실패하면_재시도하지_않고_false를_반환한다() {
        String tokenHash = "a".repeat(64);
        doThrow(new DataAccessResourceFailureException("redis down"))
                .when(refreshTokenRepository)
                .revokeIfActiveHashMatches(tokenHash, "ROLE_ADMIN", 1L);

        boolean result = sut.cleanupRedisOnce("ROLE_ADMIN", 1L, tokenHash);

        assertThat(result).isFalse();
        verify(refreshTokenRepository, times(1))
                .revokeIfActiveHashMatches(tokenHash, "ROLE_ADMIN", 1L);
    }

    @Test
    void tokenHash가_없으면_Redis를_삭제하지_않고_false를_반환한다() {
        boolean result = sut.cleanupRedisOnce("ROLE_ADMIN", 1L, null);

        assertThat(result).isFalse();
        verify(refreshTokenRepository, never())
                .revokeIfActiveHashMatches(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }
}