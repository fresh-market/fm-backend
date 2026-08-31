package com.freshmarket.member.internal.dto;

// (2026-08-18 12:35) docs/api/auth.md의 GET /v1/auth/kakao/authorize 응답.
public record KakaoAuthorizeResponse(String authorizationUrl) {
}
