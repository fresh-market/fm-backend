package com.freshmarket.member.domain.service.auth;

import com.freshmarket.member.domain.entity.Member;
import com.freshmarket.member.domain.entity.SocialType;
import com.freshmarket.member.infrastructure.kakao.oauth.KakaoAuthorizationService;
import com.freshmarket.member.infrastructure.kakao.oauth.KakaoIdTokenExchanger;
import com.freshmarket.member.infrastructure.kakao.oauth.OAuthAttributes;
import com.freshmarket.member.domain.repository.MemberGradeRepository;
import com.freshmarket.member.domain.repository.MemberRepository;
import com.freshmarket.member.domain.exception.MemberErrorCode;
import com.freshmarket.member.domain.exception.MemberException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

// docs/api/auth.md의 "POST /v1/auth/tokens"(회원 로그인 완료) 전체를 오케스트레이션한다.
// Spring Security의 oauth2Login() 필터 체인을 쓰지 않기로 하면서 — 리다이렉트 콜백을 백엔드가
// 아니라 프론트가 받는 구조라 그 필터가 원래 하던 역할이 자연스럽게 안 맞는다 — "검증된
// 사용자로 Member 조회/생성"과 "토큰 발급"이 서로 다른 시점의 필터 콜백이 아니라 이 메서드
// 안의 순서 있는 두 단계가 됐다.
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberLoginService {

    private static final String NAME_ATTRIBUTE_KEY = "sub";

    private final KakaoAuthorizationService kakaoAuthorizationService;
    private final KakaoIdTokenExchanger kakaoIdTokenExchanger;
    private final MemberRepository memberRepository;
    private final MemberGradeRepository memberGradeRepository;
    private final MemberTokenService memberTokenService;

    public String authorizationUrl(boolean forceReauth) {
        return kakaoAuthorizationService.buildAuthorizationUrl(forceReauth);
    }

    // (2026-08-19) 예전엔 이 메서드 전체가 @Transactional이었다 — 그러면 카카오 id_token 교환
    // (동기 네트워크 호출)까지 DB 트랜잭션 안에서 일어나 응답이 느려지는 동안 커넥션이 묶인다
    // (DI-4-02). @Transactional을 떼고 카카오 호출은 트랜잭션 밖에서 먼저 끝낸다 — DB 쓰기는
    // findOrCreateMember() 안의 saveAndFlush()(Spring Data JPA가 메서드 단위로 자체 트랜잭션을
    // 검)와 memberTokenService.issue()(자신의 @Transactional)가 각자 원자적으로 처리한다.
    // 대가: "회원 생성"과 "토큰 발급"이 하나의 트랜잭션으로 묶이지 않는다 — 토큰 발급만 실패하면
    // 회원 행은 남고 로그인은 실패로 끝난다. findOrCreateMember()가 activeProviderKey 유니크
    // 제약 + 레이스 시 재조회로 이미 멱등하게 짜여 있어(60~72행), 사용자가 그냥 재시도하면 같은
    // 회원을 찾아 토큰만 다시 발급받는다 — 데이터가 깨지거나 중복 생성되지 않는다. 두 트랜잭션을
    // 억지로 하나로 묶으려면 findOrCreateMember/issue를 별도 빈으로 옮겨야 하는데(self-invocation
    // 문제 때문에 같은 클래스 안에서는 안 됨), 위 리스크가 재시도로 안전하게 수렴하는 수준이라
    // 그 비용을 들일 만큼 심각하지 않다고 판단해 넘어간다.
    public LoginResult login(String authorizationCode, String state, boolean rememberMe, HttpServletResponse response) {
        Jwt idToken = kakaoIdTokenExchanger.exchange(authorizationCode, state);

        OAuthAttributes attrs = OAuthAttributes.of(SocialType.KAKAO, NAME_ATTRIBUTE_KEY, idToken.getClaims());

        Member member = findOrCreateMember(attrs);

        MemberTokenService.IssueResult issueResult = memberTokenService.issue(member, rememberMe, response);
        return new LoginResult(issueResult.accessToken(), issueResult.expiresInSeconds(), member);
    }

    private Member findOrCreateMember(OAuthAttributes attrs) {
        String activeProviderKey = Member.buildActiveProviderKey(attrs.provider(), attrs.providerUserId());
        return memberRepository.findByActiveProviderKey(activeProviderKey)
                .orElseGet(() -> registerNewMember(attrs, activeProviderKey));
    }

    private Member registerNewMember(OAuthAttributes attrs, String activeProviderKey) {
        try {
            Long defaultGradeId = memberGradeRepository.findByIsDefaultTrue()
                    .map(grade -> grade.getId())
                    .orElseThrow(() -> new MemberException(MemberErrorCode.DEFAULT_MEMBER_GRADE_NOT_FOUND));
            return memberRepository.saveAndFlush(attrs.toEntity(defaultGradeId));
        } catch (DataIntegrityViolationException e) {
            return memberRepository.findByActiveProviderKey(activeProviderKey)
                    .orElseThrow(() -> {
                        log.warn("event=MEMBER_LOGIN_FAILED reason=SIGNUP_RACE_UNRESOLVED provider={}", attrs.provider());
                        return e;
                    });
        }
    }

    public record LoginResult(String accessToken, long expiresInSeconds, Member member) {
    }
}
