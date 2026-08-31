package com.freshmarket.member.internal.dto;

import jakarta.validation.constraints.NotBlank;

// docs/api/auth.md의 POST /v1/auth/tokens 요청 본문. remember는 "자동 로그인" 여부를 전달하는
// 필드다 — 카카오 인가 요청 시작 시점의 쿼리파라미터가 아니라 로그인 완료 요청 본문에 직접 싣는다,
// 프론트가 그 값을 계속 들고 있다가 이 시점에 한 번만 넘기면 되기 때문이다.
public record MemberLoginRequest(
        @NotBlank String authorizationCode,
        @NotBlank String state,
        boolean remember
) {
}
