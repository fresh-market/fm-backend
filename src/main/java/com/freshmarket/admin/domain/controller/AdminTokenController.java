package com.freshmarket.admin.domain.controller;

import com.freshmarket.admin.domain.dto.AdminTokenResponse;
import com.freshmarket.admin.domain.exception.AdminTokenErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.service.AdminTokenService;
import com.freshmarket.common.auth.AuthCookieFactory;
import com.freshmarket.common.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 인증", description = "관리자 토큰 재발급")
@RestController
@RequestMapping("/v1/admin/auth")
@RequiredArgsConstructor
class AdminTokenController {

    private static final String REFRESH_TOKEN_COOKIE_NAME = "refreshToken";

    private final AdminTokenService adminTokenService;
    private final AuthCookieFactory authCookieFactory;

    @Operation(summary = "관리자 토큰 재발급", description = "Refresh Token을 회전하고 Access/Refresh Token을 새로 발급한다.")
    @ApiResponse(responseCode = "200", description = "재발급 성공")
    @ApiResponse(responseCode = "401", description = "만료되었거나 이미 사용된 Refresh Token (ADMIN-011)")
    @ApiResponse(responseCode = "503", description = "재발급 결과를 확인할 수 없음. 기존 Refresh Token으로 재시도하지 말고 다시 로그인해야 함 (ADMIN-012)")
    @PostMapping("/tokens:refresh")
    public ResponseEntity<ResponseEnvelope<AdminTokenResponse>> reissue(
            HttpServletRequest request,
            HttpServletResponse response) {
        String refreshToken = resolveRefreshTokenFromCookie(request);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AdminException(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);
        }

        AdminTokenService.ReissueResult result = adminTokenService.reissue(refreshToken);
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                authCookieFactory.adminRefreshTokenCookie(
                        result.refreshToken(), result.refreshTokenValiditySeconds()).toString());
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                authCookieFactory.accessTokenCookie(result.accessToken()).toString());

        return ResponseEntity.ok(ResponseEnvelope.success(new AdminTokenResponse(result.expiresInSeconds())));
    }

    private String resolveRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (REFRESH_TOKEN_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}