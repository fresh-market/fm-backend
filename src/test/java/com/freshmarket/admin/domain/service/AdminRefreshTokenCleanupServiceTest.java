package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

/*
 * DB 폐기(revokeDbWithRetry)와 Redis 정리(cleanupRedisWithRetry)의 재시도 동작을 검증한다.
 * 원래 AdminAuthServiceTest에 있던 Redis 타임아웃/연결실패 시나리오가 이 서비스로 옮겨왔다.
 */
class AdminRefreshTokenCleanupServiceTest {

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final AdminLogoutTransactionService adminLogoutTransactionService =
            mock(AdminLogoutTransactionService.class);

    private final AdminRefreshTokenCleanupService sut =
            new AdminRefreshTokenCleanupService(refreshTokenRepository, adminLogoutTransactionService);

    // ---- revokeDbWithRetry() ----

    @Test
    void DB_폐기가_첫_시도에_성공하면_바로_반환한다() {
        String tokenHash = "a".repeat(64);
        when(adminLogoutTransactionService.revokeRefreshToken(1L))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(tokenHash));

        AdminLogoutTransactionService.LogoutDbState result = sut.revokeDbWithRetry(1L);

        assertThat(result.refreshTokenHash()).isEqualTo(tokenHash);
        verify(adminLogoutTransactionService, times(1)).revokeRefreshToken(1L);
    }

    @Test
    void DB_폐기가_두번째_시도에_성공하면_거기서_멈춘다() {
        String tokenHash = "a".repeat(64);
        when(adminLogoutTransactionService.revokeRefreshToken(1L))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(tokenHash));

        AdminLogoutTransactionService.LogoutDbState result = sut.revokeDbWithRetry(1L);

        assertThat(result.refreshTokenHash()).isEqualTo(tokenHash);
        verify(adminLogoutTransactionService, times(2)).revokeRefreshToken(1L);
    }

    @Test
    void DB_폐기가_3회_모두_실패하면_null을_반환한다() {
        when(adminLogoutTransactionService.revokeRefreshToken(1L))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        AdminLogoutTransactionService.LogoutDbState result = sut.revokeDbWithRetry(1L);

        assertThat(result).isNull();
        verify(adminLogoutTransactionService, times(3)).revokeRefreshToken(1L);
    }

    @Test
    void 지연_DB폐기는_실패당시_해시를_조건으로_사용한다() {
        String tokenHash = "a".repeat(64);

        boolean result = sut.revokeDbIfMatchesWithRetry(1L, tokenHash);

        assertThat(result).isTrue();
        verify(adminLogoutTransactionService).revokeRefreshTokenIfMatches(1L, tokenHash);
    }

    @Test
    void 조건부_DB폐기가_3회_실패하면_false를_반환한다() {
        String tokenHash = "a".repeat(64);
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(adminLogoutTransactionService).revokeRefreshTokenIfMatches(1L, tokenHash);

        boolean result = sut.revokeDbIfMatchesWithRetry(1L, tokenHash);

        assertThat(result).isFalse();
        verify(adminLogoutTransactionService, times(3)).revokeRefreshTokenIfMatches(1L, tokenHash);
    }

    // ---- cleanupRedisWithRetry() ----

    @Test
    void Redis_정리가_record와_activeKey_모두_확정되면_true를_반환한다() {
        String tokenHash = "a".repeat(64);
        when(refreshTokenRepository.existsByHash(tokenHash)).thenReturn(false);

        boolean result = sut.cleanupRedisWithRetry("ROLE_ADMIN", 1L, tokenHash);

        assertThat(result).isTrue();
        verify(refreshTokenRepository)
                .revokeIfActiveHashMatches(tokenHash, "ROLE_ADMIN", 1L);
    }

    @Test
    void tokenHash가_없으면_record_삭제는_건너뛰고_activeKey만_정리한다() {
        boolean result = sut.cleanupRedisWithRetry("ROLE_ADMIN", 1L, null);

        assertThat(result).isTrue();
        verify(refreshTokenRepository, times(0)).revokeIfActiveHashMatches(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(refreshTokenRepository).deleteActiveKey("ROLE_ADMIN", 1L);
    }

    @Test
    void record_삭제가_타임아웃이면_후속조회로_삭제를_확정한다() {
        String tokenHash = "a".repeat(64);
        doThrow(new QueryTimeoutException("redis timeout"))
                .when(refreshTokenRepository)
                .revokeIfActiveHashMatches(tokenHash, "ROLE_ADMIN", 1L);
        when(refreshTokenRepository.existsByHash(tokenHash)).thenReturn(false);

        boolean result = sut.cleanupRedisWithRetry("ROLE_ADMIN", 1L, tokenHash);

        assertThat(result).isTrue();
        verify(refreshTokenRepository).existsByHash(tokenHash);
    }

    @Test
    void timeout_후_기존_RT는_삭제되고_activeKey가_새_RT를_가리키면_삭제완료로_판단한다() {
        String oldTokenHash = "a".repeat(64);
        String newTokenHash = "b".repeat(64);

        doThrow(new QueryTimeoutException("redis timeout"))
                .when(refreshTokenRepository)
                .revokeIfActiveHashMatches(
                        oldTokenHash,
                        "ROLE_ADMIN",
                        1L);

        when(refreshTokenRepository.existsByHash(oldTokenHash))
                .thenReturn(false);

        when(refreshTokenRepository.findActiveHash("ROLE_ADMIN", 1L))
                .thenReturn(Optional.of(newTokenHash));

        boolean result =
                sut.cleanupRedisWithRetry(
                        "ROLE_ADMIN",
                        1L,
                        oldTokenHash);

        assertThat(result).isTrue();

        verify(refreshTokenRepository)
                .existsByHash(oldTokenHash);

        verify(refreshTokenRepository)
                .findActiveHash("ROLE_ADMIN", 1L);
    }

    @Test
    void activeKey_삭제가_연결실패면_후속조회로_삭제를_확정한다() {
        doThrow(new DataAccessResourceFailureException("redis disconnected"))
                .when(refreshTokenRepository).deleteActiveKey("ROLE_ADMIN", 1L);
        when(refreshTokenRepository.findActiveHash("ROLE_ADMIN", 1L)).thenReturn(Optional.empty());

        boolean result = sut.cleanupRedisWithRetry("ROLE_ADMIN", 1L, null);

        assertThat(result).isTrue();
        verify(refreshTokenRepository).findActiveHash("ROLE_ADMIN", 1L);
    }

    @Test
    void record_삭제가_확정적으로_실패하면_같은_시도에서_재시도하지_않고_바깥_루프가_다시_시도한다() {
        String tokenHash = "a".repeat(64);

        DataAccessException confirmedFailure =
                new DataAccessException("redis command failed") {};

        // 1차: 확정 실패 → 내부 확인/재시도 없이 바깥 루프로 이동
        // 2차: 성공
        doThrow(confirmedFailure)
                .doNothing()
                .when(refreshTokenRepository)
                .revokeIfActiveHashMatches(tokenHash, "ROLE_ADMIN", 1L);

        boolean result =
                sut.cleanupRedisWithRetry("ROLE_ADMIN", 1L, tokenHash);

        assertThat(result).isTrue();

        verify(refreshTokenRepository, times(2))
                .revokeIfActiveHashMatches(tokenHash, "ROLE_ADMIN", 1L);
    }

    @Test
    void Redis_정리가_3회_모두_실패하면_false를_반환한다() {
        String tokenHash = "a".repeat(64);

        DataAccessException confirmedFailure =
                new DataAccessException("redis command failed") {};

        doThrow(confirmedFailure)
                .when(refreshTokenRepository)
                .revokeIfActiveHashMatches(tokenHash, "ROLE_ADMIN", 1L);

        boolean result =
                sut.cleanupRedisWithRetry("ROLE_ADMIN", 1L, tokenHash);

        assertThat(result).isFalse();

        verify(refreshTokenRepository, times(3))
                .revokeIfActiveHashMatches(tokenHash, "ROLE_ADMIN", 1L);
    }
}