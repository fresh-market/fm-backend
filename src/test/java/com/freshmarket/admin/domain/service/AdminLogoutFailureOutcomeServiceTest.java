package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.freshmarket.admin.domain.entity.AdminLogoutFailure;
import com.freshmarket.admin.domain.repository.AdminLogoutFailureRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdminLogoutFailureOutcomeServiceTest {

    private final AdminLogoutFailureRepository failureRepository = mock(AdminLogoutFailureRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final AdminLogoutFailureOutcomeService sut =
            new AdminLogoutFailureOutcomeService(failureRepository, clock);

    @Test
    void 내가_가진_lease일_때만_결과가_반영된다() {
        LocalDateTime claimedAt = LocalDateTime.of(2026, 8, 26, 9, 0);
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 9, 0);
        when(failureRepository.applyOutcomeIfClaimOwned(
                10L, claimedAt, false, false, true, "newHash", now)).thenReturn(1);

        assertThat(sut.applyOutcome(10L, claimedAt, true, true, "newHash")).isTrue();
        verify(failureRepository).applyOutcomeIfClaimOwned(
                10L, claimedAt, false, false, true, "newHash", now);
    }

    @Test
    void lease가_이미_다른_실행자에게_넘어갔으면_늦은_결과를_버린다() {
        LocalDateTime claimedAt = LocalDateTime.of(2026, 8, 26, 8, 40);
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 9, 0);
        when(failureRepository.applyOutcomeIfClaimOwned(
                10L, claimedAt, false, false, true, "hash", now)).thenReturn(0);

        assertThat(sut.applyOutcome(10L, claimedAt, true, true, "hash")).isFalse();
    }

    @Test
    void 선점_반납도_내_lease일_때만_성공한다() {
        LocalDateTime claimedAt = LocalDateTime.of(2026, 8, 26, 9, 0);
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 9, 0);
        when(failureRepository.releaseClaimIfOwned(10L, claimedAt, now)).thenReturn(1);

        assertThat(sut.releaseClaim(10L, claimedAt)).isTrue();
        verify(failureRepository).releaseClaimIfOwned(10L, claimedAt, now);
    }
}