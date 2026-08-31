package com.freshmarket.member.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.member.internal.entity.Member;
import com.freshmarket.member.internal.entity.MemberGrade;
import com.freshmarket.member.internal.entity.SocialType;
import com.freshmarket.member.internal.oauth.KakaoAuthorizationService;
import com.freshmarket.member.internal.oauth.KakaoIdTokenExchanger;
import com.freshmarket.member.internal.repository.MemberGradeRepository;
import com.freshmarket.member.internal.repository.MemberRepository;
import com.freshmarket.member.internal.exception.MemberErrorCode;
import com.freshmarket.member.internal.exception.MemberException;
import com.freshmarket.member.MemberRegisteredEvent;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

// (2026-08-18 19:10) API 점검 중 발견한 커버리지 게이트 갭(0개)을 메운다. 예전 "닉네임 레이스
// 회귀 테스트" 시도가 노렸던 것과 같은 시나리오(가입 경합)를 registerNewMember()의
// DataIntegrityViolationException 분기로 다시 커버한다 — 그 시절 파일은 이번 세션 git 사고 때
// 유실됐다.
@ExtendWith(MockitoExtension.class)
class MemberLoginServiceTest {

    private static final String ACTIVE_PROVIDER_KEY = "KAKAO:kakao-1";

    @Mock
    private KakaoAuthorizationService kakaoAuthorizationService;

    @Mock
    private KakaoIdTokenExchanger kakaoIdTokenExchanger;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private MemberGradeRepository memberGradeRepository;

    @Mock
    private MemberTokenService memberTokenService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private HttpServletResponse response;

    private MemberLoginService sut;

    @BeforeEach
    void setUp() {
        sut = new MemberLoginService(
                kakaoAuthorizationService, kakaoIdTokenExchanger, memberRepository, memberGradeRepository,
                memberTokenService, transactionTemplate, eventPublisher);
        org.mockito.Mockito.lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class));
        });
    }

    private static Jwt kakaoIdToken() {
        return Jwt.withTokenValue("id-token")
                .header("alg", "none")
                .claim("sub", "kakao-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private static Member newMember(Long gradeId) {
        return Member.register(SocialType.KAKAO, "kakao-1", gradeId);
    }

    private static MemberGrade newGrade(Long id) {
        MemberGrade grade = MemberGrade.register("브론즈", null, true);
        setId(grade, id);
        return grade;
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

    @Test
    void 이미_가입된_회원이면_새로_만들지_않고_그대로_로그인한다() {
        Member existing = newMember(1L);
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(kakaoIdToken());
        when(memberRepository.findByActiveProviderKey(ACTIVE_PROVIDER_KEY)).thenReturn(Optional.of(existing));
        when(memberTokenService.issue(existing, false, response))
                .thenReturn(new MemberTokenService.IssueResult("access-token", 1800L));

        MemberLoginService.LoginResult result = sut.login("code", "state", false, response);

        assertThat(result.member()).isSameAs(existing);
        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.expiresInSeconds()).isEqualTo(1800L);
        verify(memberGradeRepository, never()).findByIsDefaultTrue();
        verify(memberRepository, never()).saveAndFlush(any());
    }

    @Test
    void 신규_사용자면_기본_등급으로_회원을_만든다() {
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(kakaoIdToken());
        when(memberRepository.findByActiveProviderKey(ACTIVE_PROVIDER_KEY)).thenReturn(Optional.empty());
        when(memberGradeRepository.findByIsDefaultTrue()).thenReturn(Optional.of(newGrade(2L)));
        when(memberRepository.saveAndFlush(any(Member.class))).thenAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            setId(member, 3L);
            return member;
        });
        when(memberTokenService.issue(any(Member.class), eq(true), eq(response)))
                .thenReturn(new MemberTokenService.IssueResult("access-token", 1800L));

        MemberLoginService.LoginResult result = sut.login("code", "state", true, response);

        assertThat(result.member().getMemberGradeId()).isEqualTo(2L);
        assertThat(result.member().getProvider()).isEqualTo(SocialType.KAKAO);
        verify(eventPublisher).publishEvent(new MemberRegisteredEvent(3L));
    }

    @Test
    void 기본_등급이_없으면_예외() {
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(kakaoIdToken());
        when(memberRepository.findByActiveProviderKey(ACTIVE_PROVIDER_KEY)).thenReturn(Optional.empty());
        when(memberGradeRepository.findByIsDefaultTrue()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.login("code", "state", false, response))
                .isInstanceOf(MemberException.class)
                .extracting(e -> ((MemberException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.DEFAULT_MEMBER_GRADE_NOT_FOUND);
    }

    @Test
    void 가입_경합이_나면_재조회해서_먼저_가입된_회원을_돌려준다() {
        Member racedWinner = newMember(1L);
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(kakaoIdToken());
        when(memberRepository.findByActiveProviderKey(ACTIVE_PROVIDER_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(racedWinner));
        when(memberGradeRepository.findByIsDefaultTrue()).thenReturn(Optional.of(newGrade(2L)));
        when(memberRepository.saveAndFlush(any(Member.class))).thenThrow(new DataIntegrityViolationException("dup"));
        when(memberTokenService.issue(racedWinner, false, response))
                .thenReturn(new MemberTokenService.IssueResult("access-token", 1800L));

        MemberLoginService.LoginResult result = sut.login("code", "state", false, response);

        assertThat(result.member()).isSameAs(racedWinner);
    }

    @Test
    void 가입_경합_후_재조회도_실패하면_원래_예외를_던진다() {
        when(kakaoIdTokenExchanger.exchange("code", "state")).thenReturn(kakaoIdToken());
        when(memberRepository.findByActiveProviderKey(ACTIVE_PROVIDER_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(memberGradeRepository.findByIsDefaultTrue()).thenReturn(Optional.of(newGrade(2L)));
        when(memberRepository.saveAndFlush(any(Member.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> sut.login("code", "state", false, response))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void authorizationUrl은_카카오_인가_서비스에_위임한다() {
        when(kakaoAuthorizationService.buildAuthorizationUrl(true)).thenReturn("https://kauth.kakao.com/...");

        String result = sut.authorizationUrl(true);

        assertThat(result).isEqualTo("https://kauth.kakao.com/...");
    }
}
