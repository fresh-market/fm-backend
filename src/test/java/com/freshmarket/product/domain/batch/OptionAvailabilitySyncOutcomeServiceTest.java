package com.freshmarket.product.domain.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.product.domain.entity.OptionAvailabilitySyncFailure;
import com.freshmarket.product.domain.repository.OptionAvailabilitySyncFailureRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OptionAvailabilitySyncOutcomeServiceTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 8, 24, 10, 0);

    @Mock
    private OptionAvailabilitySyncFailureRepository failureRepository;

    private OptionAvailabilitySyncOutcomeService sut;

    @BeforeEach
    void setUp() {
        sut = new OptionAvailabilitySyncOutcomeService(failureRepository);
    }

    // (UT-3-04) 반복문 없이 "한도 직전(MAX_RETRY_ATTEMPTS - 1)까지 실패한" 상태를 바로 픽스처로 만든다
    private static OptionAvailabilitySyncFailure failureAtAttempt(int attemptCount) {
        OptionAvailabilitySyncFailure failure = OptionAvailabilitySyncFailure.record(11L, true, OCCURRED_AT);
        ReflectionTestUtils.setField(failure, "attemptCount", attemptCount);
        return failure;
    }

    @Test
    void 성공하면_기록을_지운다() {
        sut.markSucceeded(1L);

        verify(failureRepository).deleteById(1L);
    }

    @Test
    void 재시도_한도_전이면_카운트만_늘리고_유지한다() {
        OptionAvailabilitySyncFailure failure = OptionAvailabilitySyncFailure.record(11L, true, OCCURRED_AT);
        when(failureRepository.findById(1L)).thenReturn(Optional.of(failure));

        sut.markFailed(1L, new RuntimeException("lock timeout"));

        verify(failureRepository, never()).delete(any());
        verify(failureRepository, never()).deleteById(any());
    }

    @Test
    void 재시도_한도를_넘으면_그래도_행은_유지한다() {
        // given — 이미 4회 실패한 상태(다음 실패가 5번째, MAX_RETRY_ATTEMPTS)
        OptionAvailabilitySyncFailure failure = failureAtAttempt(4);
        when(failureRepository.findById(1L)).thenReturn(Optional.of(failure));

        sut.markFailed(1L, new RuntimeException("lock timeout"));

        // give-up 상태에서도 행 자체를 지우지는 않는다 — 사람이 보고 수동 개입할 수 있게 남겨둔다
        verify(failureRepository, never()).delete(any());
        verify(failureRepository, never()).deleteById(any());
    }

    @Test
    void 존재하지_않는_기록이면_아무_일도_하지_않는다() {
        when(failureRepository.findById(1L)).thenReturn(Optional.empty());

        sut.markFailed(1L, new RuntimeException("lock timeout"));

        verify(failureRepository, never()).delete(any());
    }
}
