package com.freshmarket.member.internal.dto;

import com.freshmarket.member.internal.entity.Member;
import com.freshmarket.member.internal.entity.MemberStatus;

// (2026-08-18 12:35) docs/api/auth.md의 로그인/재발급 성공 응답 본문(회원 요약 포함) — 원래는
// accessToken도 본문에 실었었다.
// (2026-08-18 16:20) 사용자 요청으로 accessToken 전달을 헤더에서 다시 쿠키로 되돌리면서
// accessToken 필드를 본문에서 뺐다 — 쿠키를 HttpOnly로 걸어도 응답 본문에 토큰 문자열을
// 그대로 실어주면 그 응답을 읽는 스크립트가 httpOnly와 무관하게 토큰을 그대로 얻어갈 수 있어서
// HttpOnly로 얻는 XSS 방어 효과가 없어진다.
// (2026-08-18 17:10) docs/api/auth.md도 같은 모양(accessToken/tokenType 없음)으로 갱신함 —
// 문서와 코드가 다시 맞는다.
public record MemberTokenResponse(
        long expiresInSeconds,
        MemberSummary member
) {

    public static MemberTokenResponse of(long expiresInSeconds, Member member) {
        return new MemberTokenResponse(expiresInSeconds, MemberSummary.from(member));
    }

    /** 재발급 응답에는 회원 요약이 필요 없어서(문서에 명시 안 됨) member 없이 담는다. */
    public static MemberTokenResponse withoutMember(long expiresInSeconds) {
        return new MemberTokenResponse(expiresInSeconds, null);
    }

    public record MemberSummary(Long memberId, String nickname, MemberStatus status) {
        static MemberSummary from(Member member) {
            return new MemberSummary(member.getId(), member.getNickname(), member.getStatus());
        }
    }
}
