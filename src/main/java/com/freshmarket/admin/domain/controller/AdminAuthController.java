package com.freshmarket.admin.domain.controller;

import com.freshmarket.admin.domain.dto.AdminLoginRequest;
import com.freshmarket.admin.domain.dto.AdminLoginResponse;
import com.freshmarket.admin.domain.dto.AdminLoginResult;
import com.freshmarket.admin.domain.service.AdminAuthService;
import com.freshmarket.common.auth.AuthCookieFactory;
import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/*
 * 리소스를 tokens 로 둔다 (auth.md).
 * 서버가 실제로 보관하는 것은 Redis의 리프레시 토큰 상태이며, "세션"이라는 별도 도메인 실체를 만들지 않는다.
 *
 * 이번 PR에서는 로그인과 로그아웃을 다룬다.
 * 토큰 재발급(:refresh)과 비밀번호 변경(PUT .../password)은 별도 PR에서 다룬다.
 */
@Tag(name = "관리자 인증", description = "관리자 로그인/로그아웃/토큰 재발급")
@RestController
@RequestMapping("/v1/admin/auth/tokens")
class AdminAuthController {

    private final AdminAuthService adminAuthService;
    private final AuthCookieFactory authCookieFactory;

    AdminAuthController(AdminAuthService adminAuthService, AuthCookieFactory authCookieFactory) {
        this.adminAuthService = adminAuthService;
        this.authCookieFactory = authCookieFactory;
    }

    @Operation(
            summary = "관리자 로그인",
            description = "아이디와 비밀번호로 인증해 관리자 토큰을 발급한다. 액세스 토큰과 리프레시 토큰은 "
                    + "모두 HttpOnly 쿠키로 내려간다. 5회 실패 시 잠금 정책은 이번 범위에 포함하지 않는다."
    )
    @ApiResponse(responseCode = "201", description = "발급 성공")
    @ApiResponse(responseCode = "401", description = "아이디 또는 비밀번호 불일치. 사유를 구분해 알리지 않는다 (ADMIN-001)")
    @PostMapping
    ResponseEntity<ResponseEnvelope<AdminLoginResponse>> login(
            @Valid @RequestBody AdminLoginRequest request) {
        AdminLoginResult result = adminAuthService.login(request);

        ResponseCookie accessTokenCookie = authCookieFactory.accessTokenCookie(result.accessToken());
        ResponseCookie refreshTokenCookie = authCookieFactory.adminRefreshTokenCookie(
                result.refreshToken(),
                result.refreshTokenValiditySeconds()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, accessTokenCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
                .body(ResponseEnvelope.success(result.response()));
    }

    @Operation(
            summary = "관리자 로그아웃",
            description = "현재 관리자 계정의 Refresh Token을 폐기하고 기존 Access Token도 즉시 무효화한다."
    )
    @ApiResponse(responseCode = "204", description = "로그아웃 성공")
    @ApiResponse(responseCode = "401", description = "로그인 상태가 아님")
    @ApiResponse(responseCode = "503", description = "Access Token 차단 상태를 확정할 수 없음 (ADMIN-010)")
    @DeleteMapping
    ResponseEntity<Void> logout(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletResponse response) {
        adminAuthService.logout(userDetails.getId(), userDetails.getRole());

        response.addHeader(
                HttpHeaders.SET_COOKIE,
                authCookieFactory.expiredAdminRefreshTokenCookie().toString());
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                authCookieFactory.expiredAccessTokenCookie().toString());

        return ResponseEntity.noContent().build();
    }
}