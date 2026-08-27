package com.freshmarket.member.domain.controller;

import com.freshmarket.common.auth.AuthCookieFactory;
import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.member.domain.service.auth.MemberLoginService;
import com.freshmarket.member.domain.service.auth.MemberTokenService;
import com.freshmarket.member.domain.dto.KakaoAuthorizeResponse;
import com.freshmarket.member.domain.dto.MemberLoginRequest;
import com.freshmarket.member.domain.dto.MemberTokenResponse;
import com.freshmarket.member.domain.exception.AuthErrorCode;
import com.freshmarket.member.domain.exception.AuthException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// (2026-08-18 12:40) docs/api/auth.md 기준으로 전면 재작성.
// 경로: GET /v1/auth/kakao/authorize, POST /v1/auth/tokens, POST /v1/auth/tokens:refresh,
// DELETE /v1/auth/tokens. 예전엔 로그인 자체(카카오 콜백 처리)를 Spring Security의 oauth2Login()
// 필터가 처리했지만, 이제 로그인 시작/완료 둘 다 이 컨트롤러가 평범한 REST 엔드포인트로 받는다.
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
class MemberAuthController {

    private final MemberLoginService memberLoginService;
    private final MemberTokenService memberTokenService;
    private final AuthCookieFactory authCookieFactory;

    @GetMapping("/kakao/authorize")
    public ResponseEntity<ResponseEnvelope<KakaoAuthorizeResponse>> authorize(
            @RequestParam(defaultValue = "false") boolean reauth
    ) {
        // (2026-08-18 13:40) reauth=true는 문서에 없는 파라미터 — 탈퇴 전 카카오 재인증
        // (prompt=login) 화면을 띄울 때 프론트가 붙인다. 일반 로그인 시작은 그대로 reauth 생략.
        String authorizationUrl = memberLoginService.authorizationUrl(reauth);
        return ResponseEntity.ok(ResponseEnvelope.success(new KakaoAuthorizeResponse(authorizationUrl)));
    }

    @PostMapping("/tokens")
    public ResponseEntity<ResponseEnvelope<MemberTokenResponse>> login(
            @RequestBody @Valid MemberLoginRequest request, HttpServletResponse response) {
        // (2026-08-18 16:20) accessToken 쿠키 설정은 memberTokenService.issue() 내부에서
        // refreshToken 쿠키와 함께 이미 끝난다 — 여기서 따로 Set-Cookie를 추가하지 않는다.
        MemberLoginService.LoginResult result = memberLoginService.login(
                request.authorizationCode(), request.state(), request.remember(), response);

        MemberTokenResponse body = MemberTokenResponse.of(result.expiresInSeconds(), result.member());
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(body));
    }

    // (2026-08-19) opaque 토큰 전환 이후: 리프레시 토큰이 더 이상 JWT가 아니라 여기서 서명/클레임을
    // 미리 검증할 게 없다 — 쿠키에서 꺼낸 문자열을 그대로 넘기면 memberTokenService.reissue()가
    // Redis 조회부터 시작해서 유효성/소유자를 판단한다(누구 건지도 그 조회 결과로만 안다).
    @PostMapping("/tokens:refresh")
    public ResponseEntity<ResponseEnvelope<MemberTokenResponse>> reissue(
            HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = resolveRefreshTokenFromCookie(request);
        if (refreshToken == null) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }

        MemberTokenService.ReissueResult result = memberTokenService.reissue(refreshToken);

        response.addHeader(HttpHeaders.SET_COOKIE,
                authCookieFactory.refreshTokenCookie(result.refreshToken(), result.remember()).toString());
        // (2026-08-18 16:20) issue()와 달리 reissue()는 response를 받지 않아 여기서 직접 쿠키를 얹는다.
        response.addHeader(HttpHeaders.SET_COOKIE,
                authCookieFactory.accessTokenCookie(result.accessToken()).toString());

        MemberTokenResponse body = MemberTokenResponse.withoutMember(result.expiresInSeconds());
        return ResponseEntity.ok(ResponseEnvelope.success(body));
    }

    @DeleteMapping("/tokens")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal CustomUserDetails userDetails, HttpServletResponse response) {
        memberTokenService.revoke(userDetails.getId(), userDetails.getRole(), true);

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredRefreshTokenCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredAccessTokenCookie().toString());

        return ResponseEntity.noContent().build();
    }

    private String resolveRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("refreshToken".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
