package com.freshmarket.member.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.member.internal.client.KakaoUnlinkClient;
import com.freshmarket.member.internal.entity.KakaoUnlinkFailure;
import com.freshmarket.member.internal.repository.KakaoUnlinkFailureRepository;
import com.freshmarket.member.internal.repository.MemberRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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
    @Mock private KakaoUnlinkFailureRepository failureRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private KakaoUnlinkClient kakaoUnlinkClient;
    @Mock private KakaoUnlinkRetryOutcomeService outcomeService;
    private KakaoUnlinkRetryService sut;

    @BeforeEach void setUp() {
        sut = new KakaoUnlinkRetryService(failureRepository, memberRepository, kakaoUnlinkClient, outcomeService);
    }

    private static KakaoUnlinkFailure failure(Long id) {
        KakaoUnlinkFailure failure = KakaoUnlinkFailure.record(1L, "kakao-1");
        try {
            Field field = failure.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(failure, id);
            return failure;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 최초_unlink_실패는_회원상태와_아웃박스를_함께_기록한다() {
        when(failureRepository.findByMemberId(1L)).thenReturn(Optional.empty());
        sut.recordInitialFailure(1L, "kakao-1");
        verify(memberRepository).markUnlinkFailed(1L);
        verify(failureRepository).save(any(KakaoUnlinkFailure.class));
    }

    @Test
    void 재시도_성공시_회원탈퇴_확정으로_넘긴다() {
        when(failureRepository.findByResolvedFalse()).thenReturn(List.of(failure(10L)));
        sut.retryAllPending();
        verify(outcomeService).markSucceeded(10L);
    }

    @Test
    void 재시도_실패시_실패횟수_반영으로_넘긴다() {
        when(failureRepository.findByResolvedFalse()).thenReturn(List.of(failure(10L)));
        doThrow(new RuntimeException("network")).when(kakaoUnlinkClient).unlink("kakao-1");
        sut.retryAllPending();
        verify(outcomeService).markFailed(eq(10L), any(RuntimeException.class));
    }

    @Test
    void 서킷이_열렸으면_카운트를_증가시키지_않는다() {
        when(failureRepository.findByResolvedFalse()).thenReturn(List.of(failure(10L)));
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("kakao-unlink-test");
        circuitBreaker.transitionToOpenState();
        doThrow(CallNotPermittedException.createCallNotPermittedException(circuitBreaker))
                .when(kakaoUnlinkClient).unlink("kakao-1");
        sut.retryAllPending();
        verify(outcomeService, never()).markFailed(any(), any());
    }
}
