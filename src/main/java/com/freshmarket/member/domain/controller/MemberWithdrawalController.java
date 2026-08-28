package com.freshmarket.member.domain.controller;

import com.freshmarket.common.auth.AuthCookieFactory;
import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.member.domain.service.MemberWithdrawalService;
import com.freshmarket.member.domain.dto.MemberWithdrawalRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// docs/api/member.md 기준: DELETE /members/me ->
// POST /v1/members/me:withdraw로 경로/메서드 변경(탈퇴는 리소스 필드 교체가 아니라 서버가
// 규칙대로 수행하는 동작이라 문서가 커스텀 메서드로 뒀다 — 재발급과 같은 이유, API-3-08).
// (2026-08-18 16:20) accessToken이 다시 쿠키로 나가게 되면서 expiredAccessTokenCookie() 호출도
// 복원했다 — 탈퇴 시 refreshToken뿐 아니라 accessToken 쿠키도 같이 만료시킨다.
/** 회원탈퇴 API. */
@RestController
@RequestMapping("/v1/members")
@RequiredArgsConstructor
@Tag(name = "회원", description = "회원 탈퇴")
class MemberWithdrawalController {

    private final MemberWithdrawalService memberWithdrawalService;
    private final AuthCookieFactory authCookieFactory;

    @PostMapping("/me:withdraw")
    @Operation(summary = "회원 탈퇴", description = "카카오 재인증 후 회원 계정과 현재 세션을 해제한다.")
    @ApiResponse(responseCode = "204", description = "탈퇴 성공")
    @ApiResponse(responseCode = "401", description = "카카오 재인증 정보가 유효하지 않음")
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid MemberWithdrawalRequest request,
            HttpServletResponse response
    ) {
        memberWithdrawalService.withdraw(userDetails.getId(), request.reason(), request.authorizationCode(), request.state());

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredRefreshTokenCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredAccessTokenCookie().toString());

        return ResponseEntity.noContent().build();
    }
}
