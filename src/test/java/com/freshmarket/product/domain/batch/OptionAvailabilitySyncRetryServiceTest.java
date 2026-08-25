package com.freshmarket.product.domain.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

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

    // 프로덕션 코드의 private RETRY_CHUNK_SIZE 값을 그대로 읽어, 청크 경계 테스트가 상수 변경에도 안 깨지게 한다
    private static int chunkSize() {
        try {
            Field field = OptionAvailabilitySyncRetryService.class.getDeclaredField("RETRY_CHUNK_SIZE");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 재시도가_성공하면_성공_처리로_넘긴다() {
        OptionAvailabilitySyncFailure failure = newFailure(10L, 11L, true);
        when(failureRepository.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(failure));

        sut.retryAllPending();

        verify(productOptionAvailabilityService).updateSoldOut(11L, true, OCCURRED_AT);
        verify(outcomeService).markSucceeded(10L);
        verify(outcomeService, never()).markFailed(any(), any());
    }

    @Test
    void 재시도가_또_실패하면_실패_처리로_넘긴다() {
        OptionAvailabilitySyncFailure failure = newFailure(10L, 11L, true);
        when(failureRepository.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(failure));
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
        when(failureRepository.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(f1, f2));

        sut.retryAllPending();

        verify(productOptionAvailabilityService, times(1)).updateSoldOut(11L, true, OCCURRED_AT);
        verify(productOptionAvailabilityService, times(1)).updateSoldOut(12L, false, OCCURRED_AT);
        verify(outcomeService).markSucceeded(10L);
        verify(outcomeService).markSucceeded(20L);
    }

    // (REL-2-07) 한도를 넘어 exhausted로 남은 행은 재시도하지 않는다(지우지는 않되 건너뛴다)
    @Test
    void exhausted된_행은_재시도하지_않는다() {
        OptionAvailabilitySyncFailure failure = newFailure(10L, 11L, true);
        ReflectionTestUtils.setField(failure, "exhausted", true);
        when(failureRepository.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(failure));

        sut.retryAllPending();

        verify(productOptionAvailabilityService, never()).updateSoldOut(any(), anyBoolean(), any());
        verify(outcomeService, never()).markSucceeded(any());
        verify(outcomeService, never()).markFailed(any(), any());
    }

    // (FUN-3-03/PERF-4-01/PERF-4-03) 청크가 상한만큼 차면 다음 청크를 마지막 id 기준으로 이어서 조회한다
    @Test
    void 한_청크가_상한만큼_차면_다음_id부터_이어서_조회한다() {
        int chunkSize = chunkSize();
        List<OptionAvailabilitySyncFailure> firstChunk = new ArrayList<>();
        for (int i = 0; i < chunkSize; i++) {
            firstChunk.add(newFailure((long) (i + 1), 11L, true));
        }
        long lastIdOfFirstChunk = chunkSize;
        when(failureRepository.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(firstChunk);
        when(failureRepository.findByIdGreaterThanOrderByIdAsc(eq(lastIdOfFirstChunk), any(Pageable.class)))
                .thenReturn(List.of());

        sut.retryAllPending();

        verify(failureRepository).findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class));
        verify(failureRepository).findByIdGreaterThanOrderByIdAsc(eq(lastIdOfFirstChunk), any(Pageable.class));
        verify(productOptionAvailabilityService, times(chunkSize)).updateSoldOut(eq(11L), eq(true), any());
    }
}
