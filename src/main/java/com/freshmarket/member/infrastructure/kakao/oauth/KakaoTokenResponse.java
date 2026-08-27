package com.freshmarket.member.infrastructure.kakao.oauth;

// (2026-08-18 12:15) 카카오 토큰 엔드포인트(https://kauth.kakao.com/oauth/token) 응답 중
// 우리가 실제로 쓰는 필드만 옮겨 담는다. access_token/refresh_token은 카카오 쪽 토큰이라
// 우리 서비스에서 보관하지 않는다(로그인 시점 신원 확인에만 쓰고 버린다 — auth.md 참고).
public record KakaoTokenResponse(String tokenType, String accessToken, String idToken, Long expiresIn) {
}
