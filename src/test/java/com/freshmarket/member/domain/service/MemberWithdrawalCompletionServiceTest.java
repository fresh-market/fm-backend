package com.freshmarket.member.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.freshmarket.member.domain.repository.MemberRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberWithdrawalCompletionServiceTest {
    @Mock private MemberRepository memberRepository;
    @Mock private MemberTokenService memberTokenService;
    private MemberWithdrawalCompletionService sut;

    @BeforeEach void setUp() { sut = new MemberWithdrawalCompletionService(memberRepository, memberTokenService); }

    @Test
    void 회원을_탈퇴_처리하고_토큰을_폐기한다() {
        sut.complete(1L, "ROLE_USER", "이유");
        ArgumentCaptor<LocalDateTime> deletedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(memberRepository).markWithdrawn(eq(1L), deletedAt.capture());
        assertThat(deletedAt.getValue()).isNotNull();
        verify(memberTokenService).revoke(1L, "ROLE_USER", false);
    }
}
