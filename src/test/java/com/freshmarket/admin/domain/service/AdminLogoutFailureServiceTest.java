package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminFixture;
import com.freshmarket.admin.domain.entity.AdminLogoutFailure;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.repository.AdminLogoutFailureRepository;
import com.freshmarket.admin.domain.repository.AdminRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdminLogoutFailureServiceTest {

    private final AdminLogoutFailureRepository failureRepository = mock(AdminLogoutFailureRepository.class);
    private final AdminRepository adminRepository = mock(AdminRepository.class);
    private final AdminRefreshTokenCleanupService cleanupService = mock(AdminRefreshTokenCleanupService.class);
    private final AdminLogoutFailureOutcomeService outcomeService = mock(AdminLogoutFailureOutcomeService.class);

    private final AdminLogoutFailureService sut =
            new AdminLogoutFailureService(failureRepository, adminRepository, cleanupService, outcomeService);

    private static AdminLogoutFailure newFailure(
            Long id, Long adminId, String tokenHash, boolean redisFailed, boolean dbFailed) {
        AdminLogoutFailure failure = AdminLogoutFailure.record(adminId, tokenHash, redisFailed, dbFailed);
        ReflectionTestUtils.setField(failure, "id", id);
        return failure;
    }

    private static Admin adminWithId(Long id) {
        Admin admin = AdminFixture.active("admin.kim", "hash", AdminRole.ADMIN);
        ReflectionTestUtils.setField(admin, "id", id);
        return admin;
    }

    // ---- recordFailure() ----

    @Test
    void 처음_실패한_관리자면_새_행을_만든다() {
        when(failureRepository.findByAdminId(1L)).thenReturn(Optional.empty());

        sut.recordFailure(1L, "hash", true, false);

        verify(failureRepository).save(any(AdminLogoutFailure.class));
    }

    @Test
    void 이미_행이_있으면_재오픈만_하고_새로_만들지_않는다() {
        AdminLogoutFailure existing = newFailure(10L, 1L, null, false, true);
        when(failureRepository.findByAdminId(1L)).thenReturn(Optional.of(existing));

        sut.recordFailure(1L, "hash", true, true);

        verify(failureRepository, never()).save(any());
        assertThat(existing.getRefreshTokenHash()).isEqualTo("hash");
        assertThat(existing.isRedisFailed()).isTrue();
        assertThat(existing.isDbFailed()).isTrue();
    }

    // ---- retryAllPending() ----

    @Test
    void 관리자를_찾지_못하면_재시도하지_않고_건너뛴다() {
        AdminLogoutFailure failure = newFailure(10L, 1L, null, false, true);
        when(failureRepository.findByResolvedFalse()).thenReturn(List.of(failure));
        when(failureRepository.findById(10L)).thenReturn(Optional.of(failure));
        when(adminRepository.findById(1L)).thenReturn(Optional.empty());

        sut.retryAllPending();

        verify(cleanupService, never()).revokeDbWithRetry(any());
        verify(outcomeService, never()).applyOutcome(any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void DB만_실패했던_건은_DB_재시도가_성공하면_그_해시로_Redis도_다시_정리한다() {
        AdminLogoutFailure failure = newFailure(10L, 1L, null, false, true);
        String newHash = "b".repeat(64);
        when(failureRepository.findByResolvedFalse()).thenReturn(List.of(failure));
        when(failureRepository.findById(10L)).thenReturn(Optional.of(failure));
        when(adminRepository.findById(1L)).thenReturn(Optional.of(adminWithId(1L)));
        when(cleanupService.revokeDbWithRetry(1L))
                .thenReturn(new AdminLogoutTransactionService.LogoutDbState(newHash));
        when(cleanupService.cleanupRedisWithRetry("ROLE_ADMIN", 1L, newHash)).thenReturn(true);

        sut.retryAllPending();

        verify(cleanupService).revokeDbWithRetry(1L);
        verify(cleanupService).cleanupRedisWithRetry("ROLE_ADMIN", 1L, newHash);
        verify(outcomeService).applyOutcome(10L, true, true, newHash);
    }

    @Test
    void Redis만_실패했던_건은_저장된_해시로_Redis만_다시_시도하고_DB는_건드리지_않는다() {
        String storedHash = "c".repeat(64);
        AdminLogoutFailure failure = newFailure(10L, 1L, storedHash, true, false);
        when(failureRepository.findByResolvedFalse()).thenReturn(List.of(failure));
        when(failureRepository.findById(10L)).thenReturn(Optional.of(failure));
        when(adminRepository.findById(1L)).thenReturn(Optional.of(adminWithId(1L)));
        when(cleanupService.cleanupRedisWithRetry("ROLE_ADMIN", 1L, storedHash)).thenReturn(true);

        sut.retryAllPending();

        verify(cleanupService, never()).revokeDbWithRetry(any());
        verify(cleanupService).cleanupRedisWithRetry("ROLE_ADMIN", 1L, storedHash);
        verify(outcomeService).applyOutcome(10L, true, true, storedHash);
    }

    @Test
    void DB_재시도가_이번에도_실패하면_Redis는_다시_시도하지_않고_결과만_반영한다() {
        AdminLogoutFailure failure = newFailure(10L, 1L, null, false, true);
        when(failureRepository.findByResolvedFalse()).thenReturn(List.of(failure));
        when(failureRepository.findById(10L)).thenReturn(Optional.of(failure));
        when(adminRepository.findById(1L)).thenReturn(Optional.of(adminWithId(1L)));
        when(cleanupService.revokeDbWithRetry(1L)).thenReturn(null);

        sut.retryAllPending();

        // 원래 Redis 쪽은 실패한 적이 없었으므로(redisFailed=false) 다시 건드리지 않고
        // DB만 여전히 실패로 남는다.
        verify(cleanupService, never()).cleanupRedisWithRetry(any(), any(), any());
        verify(outcomeService).applyOutcome(10L, false, true, null);
    }
}