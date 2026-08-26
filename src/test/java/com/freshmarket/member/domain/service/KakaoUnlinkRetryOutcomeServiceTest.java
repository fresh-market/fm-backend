package com.freshmarket.member.domain.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import com.freshmarket.member.domain.repository.KakaoUnlinkFailureRepository;
import java.util.Optional;

import com.freshmarket.member.domain.service.kakao.KakaoUnlinkRetryOutcomeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KakaoUnlinkRetryOutcomeServiceTest {

    @Mock
    private KakaoUnlinkFailureRepository failureRepository;

    private KakaoUnlinkRetryOutcomeService sut;

    @BeforeEach
    void setUp() {
        sut = new KakaoUnlinkRetryOutcomeService(failureRepository);
    }

    @Test
    void 성공하면_기록을_지운다() {
        sut.markSucceeded(1L);

        verify(failureRepository).deleteById(1L);
    }

    @Test
    void 재시도_한도_전이면_카운트만_늘리고_유지한다() {
        KakaoUnlinkFailure failure = KakaoUnlinkFailure.record(1L, "kakao-1");
        when(failureRepository.findById(1L)).thenReturn(Optional.of(failure));

        sut.markFailed(1L, new RuntimeException("network error"));

        verify(failureRepository, never()).delete(any());
        verify(failureRepository, never()).deleteById(any());
    }

    @Test
    void 재시도_한도를_넘으면_그래도_행은_유지한다() {
        // 5회(MAX_RETRY_ATTEMPTS)까지 계속 실패시킨 상태를 재현
        KakaoUnlinkFailure failure = KakaoUnlinkFailure.record(1L, "kakao-1");
        for (int i = 0; i < 4; i++) {
            failure.markRetryFailed();
        }
        when(failureRepository.findById(1L)).thenReturn(Optional.of(failure));

        sut.markFailed(1L, new RuntimeException("network error"));

        // give-up 상태에서도 행 자체를 지우지는 않는다 — 사람이 보고 수동 개입할 수 있게
        // 남겨둔다(ERROR 로그로 승격되는 것으로 갈음).
        verify(failureRepository, never()).delete(any());
        verify(failureRepository, never()).deleteById(any());
    }

    @Test
    void 존재하지_않는_기록이면_아무_일도_하지_않는다() {
        when(failureRepository.findById(1L)).thenReturn(Optional.empty());

        sut.markFailed(1L, new RuntimeException("network error"));

        verify(failureRepository, never()).delete(any());
    }
}
