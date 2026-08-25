package com.freshmarket.member.domain.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.member.domain.client.KakaoUnlinkClient;
import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import com.freshmarket.member.domain.repository.KakaoUnlinkFailureRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KakaoUnlinkRetryServiceTest {

    @Mock
    private KakaoUnlinkFailureRepository failureRepository;

    @Mock
    private KakaoUnlinkClient kakaoUnlinkClient;

    @Mock
    private KakaoUnlinkRetryOutcomeService outcomeService;

    private KakaoUnlinkRetryService sut;

    @BeforeEach
    void setUp() {
        sut = new KakaoUnlinkRetryService(failureRepository, kakaoUnlinkClient, outcomeService);
    }

    private static KakaoUnlinkFailure newFailure(Long id, Long memberId, String kakaoUserId) {
        KakaoUnlinkFailure failure = KakaoUnlinkFailure.record(memberId, kakaoUserId);
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
    void 처음_실패한_회원이면_새_행을_만든다() {
        when(failureRepository.findByMemberId(1L)).thenReturn(Optional.empty());

        sut.recordFailure(1L, "kakao-1");

        verify(failureRepository).save(any(KakaoUnlinkFailure.class));
    }

    @Test
    void 이미_실패_기록이_있으면_카운트만_늘리고_새로_만들지_않는다() {
        KakaoUnlinkFailure existing = newFailure(10L, 1L, "kakao-1");
        when(failureRepository.findByMemberId(1L)).thenReturn(Optional.of(existing));

        sut.recordFailure(1L, "kakao-1");

        verify(failureRepository, never()).save(any());
    }

    // ---- retryAllPending() ----

    @Test
    void 재시도가_성공하면_성공_처리로_넘긴다() {
        KakaoUnlinkFailure failure = newFailure(10L, 1L, "kakao-1");
        when(failureRepository.findByAttemptCountLessThanAndResolvedFalse(
                KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS)).thenReturn(List.of(failure));

        sut.retryAllPending();

        verify(kakaoUnlinkClient).unlink("kakao-1");
        verify(outcomeService).markSucceeded(10L);
        verify(outcomeService, never()).markFailed(any(), any());
    }

    @Test
    void 재시도가_또_실패하면_실패_처리로_넘긴다() {
        KakaoUnlinkFailure failure = newFailure(10L, 1L, "kakao-1");
        when(failureRepository.findByAttemptCountLessThanAndResolvedFalse(
                KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS)).thenReturn(List.of(failure));
        doThrow(new RuntimeException("network error")).when(kakaoUnlinkClient).unlink("kakao-1");

        sut.retryAllPending();

        verify(outcomeService).markFailed(eq(10L), any(RuntimeException.class));
        verify(outcomeService, never()).markSucceeded(any());
    }

    @Test
    void 미완료_건이_여러개면_전부_처리한다() {
        KakaoUnlinkFailure f1 = newFailure(10L, 1L, "kakao-1");
        KakaoUnlinkFailure f2 = newFailure(11L, 2L, "kakao-2");
        when(failureRepository.findByAttemptCountLessThanAndResolvedFalse(
                KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS)).thenReturn(List.of(f1, f2));

        sut.retryAllPending();

        verify(kakaoUnlinkClient, times(2)).unlink(any());
        verify(outcomeService).markSucceeded(10L);
        verify(outcomeService).markSucceeded(11L);
    }

    @Test
    void 재시도_대상만_조회한다() {
        when(failureRepository.findByAttemptCountLessThanAndResolvedFalse(
                KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS)).thenReturn(List.of());

        sut.retryAllPending();

        verify(failureRepository).findByAttemptCountLessThanAndResolvedFalse(
                KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS);
        verify(failureRepository, never()).findAll();
    }
}
