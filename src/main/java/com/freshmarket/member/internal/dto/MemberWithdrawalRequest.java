package com.freshmarket.member.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// (2026-08-18 13:40) docs/api/member.md 탈퇴 요청 본문은 {"reason": "..."}뿐이지만, 문서가
// 별도로 요구하는 "탈퇴 전 카카오 재인증(prompt=login)"을 서버가 검증하려면 그 재인증에서 받은
// authorizationCode/state가 같이 필요하다(사용자 확인: "탈퇴 요청에 신선 id_token 포함") —
// 프론트는 GET /v1/auth/kakao/authorize?reauth=true로 재로그인시킨 뒤 그 결과인 code/state를
// 여기 실어 보낸다.
public record MemberWithdrawalRequest(
        @Size(max = 255) String reason,
        @NotBlank String authorizationCode,
        @NotBlank String state
) {
}
