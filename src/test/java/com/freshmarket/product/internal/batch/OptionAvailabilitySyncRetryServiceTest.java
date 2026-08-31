package com.freshmarket.product.internal.batch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.product.internal.entity.OptionAvailabilitySyncFailure;
import com.freshmarket.product.internal.repository.OptionAvailabilitySyncFailureRepository;
import com.freshmarket.product.internal.service.ProductOptionAvailabilityService;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OptionAvailabilitySyncRetryServiceTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 8, 24, 10, 0);

    @Mock
    private OptionAvailabilitySyncFailureRepository failureRepository;

    @Mock
    private ProductOptionAvailabilityService productOptionAvailabilityService;

    @Mock
    private OptionAvailabilitySyncOutcomeService outcomeService;

    private OptionAvailabilitySyncRetryService sut;

    @BeforeEach
    void setUp() {
        sut = new OptionAvailabilitySyncRetryService(failureRepository, productOptionAvailabilityService,
                outcomeService);
    }

    private static OptionAvailabilitySyncFailure newFailure(Long id, Long productOptionId, boolean soldOut) {
        OptionAvailabilitySyncFailure failure =
                OptionAvailabilitySyncFailure.record(productOptionId, soldOut, OCCURRED_AT);
        try {
            Field field = failure.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(failure, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return failure;
    }

    // ---- recordFailure() ----

    @Test
    void 처음_실패한_옵션이면_새_행을_만든다() {
        when(failureRepository.findByProductOptionId(11L)).thenReturn(Optional.empty());

        sut.recordFailure(11L, true, OCCURRED_AT);

        verify(failureRepository).save(any(OptionAvailabilitySyncFailure.class));
    }

    @Test
    void 이미_실패_기록이_있으면_최신_값으로_덮어쓰고_새로_만들지_않는다() {
        OptionAvailabilitySyncFailure existing = newFailure(10L, 11L, true);
        when(failureRepository.findByProductOptionId(11L)).thenReturn(Optional.of(existing));

        sut.recordFailure(11L, false, OCCURRED_AT.plusMinutes(1));

        verify(failureRepository, never()).save(any());
    }

    // ---- retryAllPending() ----

    private void stubPage(Long afterId, List<OptionAvailabilitySyncFailure> content) {
        when(failureRepository.findByIdGreaterThanAndAttemptCountLessThanOrderByIdAsc(eq(afterId), anyInt(), any()))
                .thenReturn(content);
    }

    @Test
    void 재시도가_성공하면_성공_처리로_넘긴다() {
        OptionAvailabilitySyncFailure failure = newFailure(10L, 11L, true);
        stubPage(0L, List.of(failure));
        stubPage(10L, List.of());

        sut.retryAllPending();

        verify(productOptionAvailabilityService).updateSoldOut(11L, true, OCCURRED_AT);
        verify(outcomeService).markSucceeded(10L);
        verify(outcomeService, never()).markFailed(any(), any());
    }

    @Test
    void 재시도가_또_실패하면_실패_처리로_넘긴다() {
        OptionAvailabilitySyncFailure failure = newFailure(10L, 11L, true);
        stubPage(0L, List.of(failure));
        stubPage(10L, List.of());
        doThrow(new RuntimeException("lock timeout")).when(productOptionAvailabilityService)
                .updateSoldOut(11L, true, OCCURRED_AT);

        sut.retryAllPending();

        verify(outcomeService).markFailed(eq(10L), any(RuntimeException.class));
        verify(outcomeService, never()).markSucceeded(any());
    }

    // 동기화 자체는 성공했는데 markSucceeded()가 실패해도 markFailed()로 넘어가면 안 된다(잘못된 실패 카운트 방지)
    @Test
    void 성공_처리_자체가_실패해도_실패_처리로_넘기지_않는다() {
        OptionAvailabilitySyncFailure failure = newFailure(10L, 11L, true);
        stubPage(0L, List.of(failure));
        stubPage(10L, List.of());
        doThrow(new RuntimeException("deadlock")).when(outcomeService).markSucceeded(10L);

        sut.retryAllPending();

        verify(outcomeService, never()).markFailed(any(), any());
    }

    // markFailed() 자체가 던져도 배치(retryAllPending) 밖으로 전파되면 안 된다 — 나머지 대기 건이 스킵되는 걸 막는다
    @Test
    void 실패_처리_자체가_실패해도_배치_전체를_중단시키지_않는다() {
        OptionAvailabilitySyncFailure f1 = newFailure(10L, 11L, true);
        OptionAvailabilitySyncFailure f2 = newFailure(20L, 12L, false);
        stubPage(0L, List.of(f1, f2));
        stubPage(20L, List.of());
        doThrow(new RuntimeException("lock timeout")).when(productOptionAvailabilityService)
                .updateSoldOut(11L, true, OCCURRED_AT);
        doThrow(new RuntimeException("outcome save failed")).when(outcomeService)
                .markFailed(eq(10L), any());

        assertThatCode(() -> sut.retryAllPending()).doesNotThrowAnyException();

        verify(productOptionAvailabilityService).updateSoldOut(12L, false, OCCURRED_AT);
        verify(outcomeService).markSucceeded(20L);
    }

    @Test
    void 미완료_건이_한_페이지에_여러개면_전부_처리한다() {
        OptionAvailabilitySyncFailure f1 = newFailure(10L, 11L, true);
        OptionAvailabilitySyncFailure f2 = newFailure(20L, 12L, false);
        stubPage(0L, List.of(f1, f2));
        stubPage(20L, List.of());

        sut.retryAllPending();

        verify(productOptionAvailabilityService, times(1)).updateSoldOut(11L, true, OCCURRED_AT);
        verify(productOptionAvailabilityService, times(1)).updateSoldOut(12L, false, OCCURRED_AT);
        verify(outcomeService).markSucceeded(10L);
        verify(outcomeService).markSucceeded(20L);
    }

    // (PERF-4-03) 페이지 경계를 넘는 미완료 건도 id 커서로 이어서 다음 페이지까지 처리하는지 검증한다
    @Test
    void 페이지_경계를_넘는_미완료_건도_커서로_이어서_처리한다() {
        OptionAvailabilitySyncFailure f1 = newFailure(10L, 11L, true);
        OptionAvailabilitySyncFailure f2 = newFailure(20L, 12L, false);
        stubPage(0L, List.of(f1));
        stubPage(10L, List.of(f2));
        stubPage(20L, List.of());

        sut.retryAllPending();

        verify(productOptionAvailabilityService).updateSoldOut(11L, true, OCCURRED_AT);
        verify(productOptionAvailabilityService).updateSoldOut(12L, false, OCCURRED_AT);
    }

    // (REL-2-07) 재시도 한도를 조회 조건으로 넘기는지 검증한다 — 한도를 넘긴 행은 조회 자체에서 빠진다
    @Test
    void 재시도_한도를_조회_조건으로_넘긴다() {
        stubPage(0L, List.of());

        sut.retryAllPending();

        verify(failureRepository).findByIdGreaterThanAndAttemptCountLessThanOrderByIdAsc(
                eq(0L), eq(OptionAvailabilitySyncFailure.MAX_RETRY_ATTEMPTS), any());
    }
}
