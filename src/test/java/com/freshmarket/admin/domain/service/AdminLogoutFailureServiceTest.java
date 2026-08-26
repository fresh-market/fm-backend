package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminFixture;
import com.freshmarket.admin.domain.entity.AdminLogoutFailure;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.repository.AdminLogoutFailureRepository;
import com.freshmarket.admin.domain.repository.AdminRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdminLogoutFailureServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime CLAIMED_AT = LocalDateTime.of(2026, 8, 26, 0, 0);
    private static final LocalDateTime STALE_BEFORE = LocalDateTime.of(2026, 8, 25, 23, 50);

    private final AdminLogoutFailureRepository failureRepository = mock(AdminLogoutFailureRepository.class);
    private final AdminRepository adminRepository = mock(AdminRepository.class);
    private final AdminRefreshTokenCleanupService cleanupService = mock(AdminRefreshTokenCleanupService.class);
    private final AdminLogoutFailureOutcomeService outcomeService = mock(AdminLogoutFailureOutcomeService.class);

    private final AdminLogoutFailureService sut =
            new AdminLogoutFailureService(failureRepository, adminRepository, cleanupService, outcomeService, CLOCK);

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

    private void pending(AdminLogoutFailure failure) {
        when(failureRepository.findTop100ByResolvedFalseAndIdGreaterThanOrderByIdAsc(0L))
                .thenReturn(List.of(failure));
        when(failureRepository.claimForRetry(failure.getId(), CLAIMED_AT, STALE_BEFORE)).thenReturn(1);
        when(failureRepository.findById(failure.getId())).thenReturn(Optional.of(failure));
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
    void 선점에_실패하면_외부_정리를_실행하지_않는다() {
        AdminLogoutFailure failure = newFailure(10L, 1L, null, false, true);
        when(failureRepository.findTop100ByResolvedFalseAndIdGreaterThanOrderByIdAsc(0L))
                .thenReturn(List.of(failure));
        when(failureRepository.claimForRetry(10L, CLAIMED_AT, STALE_BEFORE)).thenReturn(0);

        sut.retryAllPending();

        verify(failureRepository, never()).findById(10L);
        verify(cleanupService, never()).revokeDbIfMatchesWithRetry(any(), any());
        verify(cleanupService, never()).cleanupRedisWithRetry(any(), any(), any());
    }

    @Test
    void 관리자를_찾지_못하면_재시도하지_않고_선점을_반납한다() {
        AdminLogoutFailure failure = newFailure(10L, 1L, null, false, true);
        pending(failure);
        when(adminRepository.findById(1L)).thenReturn(Optional.empty());

        sut.retryAllPending();

        verify(cleanupService, never()).revokeDbIfMatchesWithRetry(any(), any());
        verify(outcomeService).releaseClaim(10L);
        verify(outcomeService, never()).applyOutcome(any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void DB만_실패했던_건은_실패당시_해시가_같을때만_DB를_재시도한다() {
        String storedHash = "b".repeat(64);
        AdminLogoutFailure failure = newFailure(10L, 1L, storedHash, false, true);
        pending(failure);
        when(adminRepository.findById(1L)).thenReturn(Optional.of(adminWithId(1L)));
        when(cleanupService.revokeDbIfMatchesWithRetry(1L, storedHash)).thenReturn(true);

        sut.retryAllPending();

        verify(cleanupService).revokeDbIfMatchesWithRetry(1L, storedHash);
        verify(cleanupService, never()).cleanupRedisWithRetry(any(), any(), any());
        verify(outcomeService).applyOutcome(10L, true, true, storedHash);
    }

    @Test
    void Redis만_실패했던_건은_저장된_해시로_Redis만_다시_시도하고_DB는_건드리지_않는다() {
        String storedHash = "c".repeat(64);
        AdminLogoutFailure failure = newFailure(10L, 1L, storedHash, true, false);
        pending(failure);
        when(adminRepository.findById(1L)).thenReturn(Optional.of(adminWithId(1L)));
        when(cleanupService.cleanupRedisWithRetry("ROLE_ADMIN", 1L, storedHash)).thenReturn(true);

        sut.retryAllPending();

        verify(cleanupService, never()).revokeDbIfMatchesWithRetry(any(), any());
        verify(cleanupService).cleanupRedisWithRetry("ROLE_ADMIN", 1L, storedHash);
        verify(outcomeService).applyOutcome(10L, true, true, storedHash);
    }

    @Test
    void DB_재시도가_이번에도_실패하면_Redis는_다시_시도하지_않고_결과만_반영한다() {
        String storedHash = "d".repeat(64);
        AdminLogoutFailure failure = newFailure(10L, 1L, storedHash, false, true);
        pending(failure);
        when(adminRepository.findById(1L)).thenReturn(Optional.of(adminWithId(1L)));
        when(cleanupService.revokeDbIfMatchesWithRetry(1L, storedHash)).thenReturn(false);

        sut.retryAllPending();

        verify(cleanupService, never()).cleanupRedisWithRetry(any(), any(), any());
        verify(outcomeService).applyOutcome(10L, false, true, storedHash);
    }

    @Test
    void DB실패_기록에_과거해시가_없으면_현재_RT를_무조건_삭제하지_않는다() {
        AdminLogoutFailure failure = newFailure(10L, 1L, null, false, true);
        pending(failure);
        when(adminRepository.findById(1L)).thenReturn(Optional.of(adminWithId(1L)));

        sut.retryAllPending();

        verify(cleanupService, never()).revokeDbIfMatchesWithRetry(any(), any());
        verify(outcomeService).applyOutcome(10L, false, true, null);
    }

    @Test
    void 미해결_건은_100건씩_PK_커서로_나눠_조회한다() {
        List<AdminLogoutFailure> firstBatch = LongStream.rangeClosed(1, 100)
                .mapToObj(id -> newFailure(id, id, "hash", true, false))
                .toList();
        when(failureRepository.findTop100ByResolvedFalseAndIdGreaterThanOrderByIdAsc(0L))
                .thenReturn(firstBatch);
        when(failureRepository.findTop100ByResolvedFalseAndIdGreaterThanOrderByIdAsc(100L))
                .thenReturn(List.of());
        when(failureRepository.claimForRetry(any(), any(), any())).thenReturn(0);

        sut.retryAllPending();

        verify(failureRepository).findTop100ByResolvedFalseAndIdGreaterThanOrderByIdAsc(0L);
        verify(failureRepository).findTop100ByResolvedFalseAndIdGreaterThanOrderByIdAsc(100L);
        verify(failureRepository, times(100)).claimForRetry(any(), any(), any());
    }

    @Test
    void 선점_후_실패_행이_없어졌으면_외부_정리를_실행하지_않는다() {
        AdminLogoutFailure failure =
                newFailure(10L, 1L, null, false, true);

        when(failureRepository
                .findTop100ByResolvedFalseAndIdGreaterThanOrderByIdAsc(0L))
                .thenReturn(List.of(failure));

        when(failureRepository.claimForRetry(
                10L,
                CLAIMED_AT,
                STALE_BEFORE))
                .thenReturn(1);

        when(failureRepository.findById(10L))
                .thenReturn(Optional.empty());

        sut.retryAllPending();

        verify(cleanupService, never())
                .revokeDbIfMatchesWithRetry(any(), any());

        verify(cleanupService, never())
                .cleanupRedisWithRetry(any(), any(), any());

        verify(outcomeService, never())
                .applyOutcome(any(), anyBoolean(), anyBoolean(), any());
    }
}