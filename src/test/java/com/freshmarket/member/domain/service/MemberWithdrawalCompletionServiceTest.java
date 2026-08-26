package com.freshmarket.member.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.freshmarket.member.domain.event.MemberWithdrawalEvent;
import com.freshmarket.member.domain.repository.MemberRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

// (2026-08-19) MemberWithdrawalService.withdraw()에서 카카오 재인증 이후의 쓰기(DB 상태 변경 +
// 토큰 폐기 + unlink 이벤트 발행)를 여기로 옮겼다 — DI-4-02 정리 참고(MemberWithdrawalCompletionService
// 클래스 주석). domain.service 소속이라 100% 메서드 커버리지 게이트 대상이라 반드시 직접 테스트가
// 있어야 한다.
@ExtendWith(MockitoExtension.class)
class MemberWithdrawalCompletionServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private MemberTokenService memberTokenService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MemberWithdrawalCompletionService sut;

    @BeforeEach
    void setUp() {
        sut = new MemberWithdrawalCompletionService(memberRepository, memberTokenService, eventPublisher);
    }

    @Test
    void 회원을_탈퇴_처리하고_토큰을_폐기하고_unlink_이벤트를_발행한다() {
        sut.complete(1L, "kakao-1", "ROLE_USER", "이유");

        ArgumentCaptor<LocalDateTime> deletedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(memberRepository).markWithdrawn(eq(1L), deletedAtCaptor.capture());
        assertThat(deletedAtCaptor.getValue()).isNotNull();

        verify(memberTokenService).revoke(1L, "ROLE_USER", false);
        verify(eventPublisher).publishEvent(new MemberWithdrawalEvent(1L, "kakao-1"));
    }
}
