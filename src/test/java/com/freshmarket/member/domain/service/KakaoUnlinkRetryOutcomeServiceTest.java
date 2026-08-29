package com.freshmarket.member.domain.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import com.freshmarket.member.domain.repository.KakaoUnlinkFailureRepository;
import com.freshmarket.member.domain.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KakaoUnlinkRetryOutcomeServiceTest {
    @Mock private KakaoUnlinkFailureRepository failureRepository;
    @Mock private MemberRepository memberRepository;
    private KakaoUnlinkRetryOutcomeService sut;

    @BeforeEach void setUp() { sut = new KakaoUnlinkRetryOutcomeService(failureRepository, memberRepository); }

    @Test
    void 성공하면_회원탈퇴를_확정하고_아웃박스를_지운다() {
        KakaoUnlinkFailure failure = KakaoUnlinkFailure.record(1L, "kakao-1");
        when(failureRepository.findById(1L)).thenReturn(Optional.of(failure));
        when(memberRepository.markWithdrawnAfterUnlink(1L)).thenReturn(1);
        sut.markSucceeded(1L);
        verify(failureRepository).delete(failure);
    }

    @Test
    void 상태를_확정하지_못하면_아웃박스를_유지한다() {
        KakaoUnlinkFailure failure = KakaoUnlinkFailure.record(1L, "kakao-1");
        when(failureRepository.findById(1L)).thenReturn(Optional.of(failure));
        when(memberRepository.markWithdrawnAfterUnlink(1L)).thenReturn(0);
        sut.markSucceeded(1L);
        verify(failureRepository, never()).delete(any());
    }

    @Test
    void 재시도_실패시_아웃박스를_유지한다() {
        KakaoUnlinkFailure failure = KakaoUnlinkFailure.record(1L, "kakao-1");
        when(failureRepository.findById(1L)).thenReturn(Optional.of(failure));
        sut.markFailed(1L, new RuntimeException("network"));
        verify(failureRepository, never()).delete(any());
    }
}
