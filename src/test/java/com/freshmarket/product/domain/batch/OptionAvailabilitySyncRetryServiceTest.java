package com.freshmarket.product.domain.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.product.domain.entity.OptionAvailabilitySyncFailure;
import com.freshmarket.product.domain.repository.OptionAvailabilitySyncFailureRepository;
import com.freshmarket.product.domain.service.ProductOptionAvailabilityService;
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

    @Test
    void 재시도가_성공하면_성공_처리로_넘긴다() {
        OptionAvailabilitySyncFailure failure = newFailure(10L, 11L, true);
        when(failureRepository.findAll()).thenReturn(List.of(failure));

        sut.retryAllPending();

        verify(productOptionAvailabilityService).updateSoldOut(11L, true, OCCURRED_AT);
        verify(outcomeService).markSucceeded(10L);
        verify(outcomeService, never()).markFailed(any(), any());
    }

    @Test
    void 재시도가_또_실패하면_실패_처리로_넘긴다() {
        OptionAvailabilitySyncFailure failure = newFailure(10L, 11L, true);
        when(failureRepository.findAll()).thenReturn(List.of(failure));
        doThrow(new RuntimeException("lock timeout")).when(productOptionAvailabilityService)
                .updateSoldOut(11L, true, OCCURRED_AT);

        sut.retryAllPending();

        verify(outcomeService).markFailed(eq(10L), any(RuntimeException.class));
        verify(outcomeService, never()).markSucceeded(any());
    }

    @Test
    void 미완료_건이_여러개면_전부_처리한다() {
        OptionAvailabilitySyncFailure f1 = newFailure(10L, 11L, true);
        OptionAvailabilitySyncFailure f2 = newFailure(20L, 12L, false);
        when(failureRepository.findAll()).thenReturn(List.of(f1, f2));

        sut.retryAllPending();

        verify(productOptionAvailabilityService, times(1)).updateSoldOut(11L, true, OCCURRED_AT);
        verify(productOptionAvailabilityService, times(1)).updateSoldOut(12L, false, OCCURRED_AT);
        verify(outcomeService).markSucceeded(10L);
        verify(outcomeService).markSucceeded(20L);
    }
}
