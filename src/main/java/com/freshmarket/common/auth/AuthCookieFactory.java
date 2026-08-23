package com.freshmarket.common.auth;

import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

// accessToken/refreshToken 둘 다 HttpOnly 쿠키로 내려준다 — 응답 본문에는 토큰 문자열을
// 싣지 않는다(MemberTokenResponse 참고). 본문에 실으면 그 응답을 읽는 스크립트가 httpOnly
// 여부와 무관하게 토큰을 그대로 얻어갈 수 있어 httpOnly로 얻는 XSS 방어 효과가 없어진다.
// 대신 accessToken도 쿠키인 이상 CSRF 노출 범위가 인증이 필요한 전체 API로 넓어진다 —
// SameSite=Strict가 대부분을 막아주지만 완전한 방어는 아니다. CSRF 토큰(더블서밋 쿠키 등)
// 도입 여부는 docs/api/auth.md의 "정하지 못한 것"에 열린 채로 남아 있다.
/**
 * accessToken/refreshToken 쿠키를 만들고 지우는 로직을 한 곳에 모은 것.
 */
@Component
@RequiredArgsConstructor
public class AuthCookieFactory {

    // RFC 6265 §5.1.4의 path-match는 "경로가 같거나, 쿠키 path가 '/'로 끝나거나, 요청 path의
    // 다음 글자가 '/'여야" 성립한다. 재발급 경로가 /v1/auth/tokens:refresh(콜론 커스텀 메서드,
    // AIP-136)라 다음 글자가 ':'라서 셋 다 성립하지 않는다 — /v1/auth/tokens로 좁혀두면
    // POST/DELETE /v1/auth/tokens에는 실려도 :refresh에는 절대 안 실린다. 콜론 뒤는 구조적으로
    // '/'가 아니라서 이보다 좁게 잡을 방법이 없다 — /v1/auth/ 로 넓혀야 세 요청(POST/DELETE
    // /v1/auth/tokens, POST /v1/auth/tokens:refresh) 전부를 커버한다. GET /v1/auth/kakao/authorize
    // 같은 다른 /v1/auth/* 요청에도 같이 실리지만, HttpOnly+SameSite=Strict라 CSRF 노출은 안
    // 늘어나고 XSS는 애초에 Path 스코프로 막던 게 아니었다(같은 오리진 스크립트는 좁은 Path였어도
    // 정확한 URL로 직접 호출 가능) — 남는 비용은 앞으로 /v1/auth/ 아래 추가되는 엔드포인트가
    // 의식하지 않아도 이 쿠키를 자동으로 받게 된다는 것뿐이다.
    private static final String REFRESH_TOKEN_COOKIE_PATH = "/v1/auth/";
    private static final String ADMIN_REFRESH_TOKEN_COOKIE_PATH = "/v1/admin/auth/";
    // accessToken은 재발급/로그아웃 경로만이 아니라 인증이 필요한 모든 API 요청에 실려야 한다.
    private static final String ACCESS_TOKEN_COOKIE_PATH = "/";

    private final JwtTokenProvider jwtTokenProvider;

    @Value("${jwt.cookie.secure:false}")
    private boolean secure; // TODO: 운영(https)에서는 반드시 true

    public ResponseCookie accessTokenCookie(String accessToken) {
        return ResponseCookie.from("accessToken", accessToken)
                .httpOnly(true)
                .secure(secure)
                .path(ACCESS_TOKEN_COOKIE_PATH)
                .sameSite("Strict")
                .maxAge(Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()))
                .build();
    }

    public ResponseCookie expiredAccessTokenCookie() {
        return ResponseCookie.from("accessToken", "")
                .httpOnly(true)
                .secure(secure)
                .path(ACCESS_TOKEN_COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .sameSite("Strict")
                .build();
    }

    public ResponseCookie refreshTokenCookie(String refreshToken, boolean persistent) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(secure)
                .path(REFRESH_TOKEN_COOKIE_PATH)
                .sameSite("Strict");
        if (persistent) {
            builder.maxAge(Duration.ofMillis(jwtTokenProvider.getRefreshTokenValidityMs()));
        }
        return builder.build();
    }

    public ResponseCookie adminRefreshTokenCookie(String refreshToken, long validitySeconds) {
        return ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(secure)
                .path(ADMIN_REFRESH_TOKEN_COOKIE_PATH)
                .sameSite("Strict")
                .maxAge(Duration.ofSeconds(validitySeconds))
                .build();
    }

    public ResponseCookie expiredRefreshTokenCookie() {
        return ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(secure)
                .path(REFRESH_TOKEN_COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .sameSite("Strict")
                .build();
    }

    public ResponseCookie expiredAdminRefreshTokenCookie() {
        return ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(secure)
                .path(ADMIN_REFRESH_TOKEN_COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .sameSite("Strict")
                .build();
    }
}
