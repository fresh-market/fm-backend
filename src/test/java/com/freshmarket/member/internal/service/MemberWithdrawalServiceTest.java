package com.freshmarket.member.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.member.internal.entity.Member;
import com.freshmarket.member.internal.entity.SocialType;
import com.freshmarket.member.internal.client.KakaoUnlinkClient;
import com.freshmarket.member.internal.oauth.KakaoIdTokenExchanger;
import com.freshmarket.member.internal.repository.MemberRepository;
import com.freshmarket.member.internal.exception.AuthErrorCode;
import com.freshmarket.member.internal.exception.AuthException;
import com.freshmarket.member.internal.exception.MemberErrorCode;
import com.freshmarket.member.internal.exception.MemberException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

// (2026-08-18 19:10) API 점검 중 발견한 커버리지 게이트 갭(0개)을 메운다. 탈퇴 전 카카오
// 재인증(본인 확인) 검증이 이 세션에서 새로 추가된 요구사항이라 그 분기를 중점적으로 본다.
@ExtendWith(MockitoExtension.class)
class MemberWithdrawalServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private MemberTokenService memberTokenService;

    @Mock
    private KakaoIdTokenExchanger kakaoIdTokenExchanger;

    @Mock
    private MemberWithdrawalCompletionService memberWithdrawalCompletionService;

    @Mock
    private KakaoUnlinkClient kakaoUnlinkClient;

    @Mock
    private KakaoUnlinkRetryService kakaoUnlinkRetryService;

    private MemberWithdrawalService sut;

    @BeforeEach
    void setUp() {
        sut = new MemberWithdrawalService(memberRepository, memberTokenService, kakaoIdTokenExchanger,
                memberWithdrawalCompletionService, kakaoUnlinkClient, kakaoUnlinkRetryService);
    }

    private static Member newMember(Long id) {
        Member member = Member.register(SocialType.KAKAO, "kakao-1", 1L);
        setId(member, id);
        return member;
    }

    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Jwt idTokenWithSub(String sub) {
        return Jwt.withTokenValue("id-token")
                .header("alg", "none")
                .claim("sub", sub)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    @Test
    void 존재하지_않는_회원이면_예외() {
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.withdraw(1L, "이유", "code", "state"))
                .isInstanceOf(MemberException.class)
                .extracting(e -> ((MemberException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    void 이미_탈퇴한_회원이면_예외() {
        Member member = newMember(1L);
        member.withdraw();
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> sut.withdraw(1L, "이유", "code", "state"))
                .isInstanceOf(MemberException.class)
                .extracting(e -> ((MemberException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_ALREADY_WITHDRAWN);
    }

    @Test
    void 재인증한_카카오_계정이_본인과_다르면_예외() {
        Member member = newMember(1L);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(idTokenWithSub("다른-kakao-id"));

        assertThatThrownBy(() -> sut.withdraw(1L, "이유", "code", "state"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REAUTH_ACCOUNT_MISMATCH);
        assertThat(member.isWithdrawn()).isFalse();
    }

    // (2026-08-19) DI-4-02 정리로 카카오 재인증 이후의 실제 쓰기(탈퇴 처리/토큰 폐기/이벤트 발행)가
    // MemberWithdrawalCompletionService(별도 트랜잭션 빈)로 옮겨갔다 — 여기서는 withdraw()가
    // 재인증까지 통과했을 때 그 위임을 올바른 인자로 호출하는지만 본다. 실제 쓰기 로직 자체는
    // MemberWithdrawalCompletionServiceTest가 검증한다.
    @Test
    void 재인증까지_통과하면_탈퇴_완료_처리를_위임한다() {
        Member member = newMember(1L);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(idTokenWithSub("kakao-1"));

        sut.withdraw(1L, "이유", "code", "state");

        verify(memberWithdrawalCompletionService).complete(1L, "ROLE_USER", "이유");
        verify(kakaoUnlinkClient).unlink("kakao-1");
    }

    @Test
    void unlink_실패시_실패상태와_아웃박스를_기록하고_예외를_전파한다() {
        Member member = newMember(1L);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(idTokenWithSub("kakao-1"));
        RuntimeException failure = new RuntimeException("kakao unavailable");
        org.mockito.Mockito.doThrow(failure).when(kakaoUnlinkClient).unlink("kakao-1");

        assertThatThrownBy(() -> sut.withdraw(1L, "이유", "code", "state")).isSameAs(failure);

        verify(kakaoUnlinkRetryService).recordInitialFailure(1L, "kakao-1");
    }

    @Test
    void 웹훅으로_들어오면_재인증_없이_바로_탈퇴_처리한다() {
        Member member = newMember(1L);
        when(memberRepository.findByActiveProviderKey("KAKAO:kakao-1")).thenReturn(Optional.of(member));

        sut.withdrawByKakaoWebhook("kakao-1");

        assertThat(member.isWithdrawn()).isTrue();
        verify(memberTokenService).revoke(1L, "ROLE_USER", false);
        verify(kakaoIdTokenExchanger, never()).exchange(anyString(), anyString());
    }

    @Test
    void 웹훅_대상_회원이_없어도_예외를_던지지_않는다() {
        when(memberRepository.findByActiveProviderKey("KAKAO:kakao-1")).thenReturn(Optional.empty());

        assertThatCode(() -> sut.withdrawByKakaoWebhook("kakao-1")).doesNotThrowAnyException();
        verify(memberTokenService, never()).revoke(any(), any(), anyBoolean());
    }
}
