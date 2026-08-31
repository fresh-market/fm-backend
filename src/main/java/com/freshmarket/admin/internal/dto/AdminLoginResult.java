package com.freshmarket.admin.internal.dto;

/*
 * AdminAuthService 와 AdminAuthController 사이에서만 쓴다. REST 응답으로 직렬화되지 않는다.
 * accessToken/refreshToken은 컨트롤러가 HttpOnly 쿠키를 만드는 데만 쓰고, response(실제 응답 본문)에는 담기지 않는다.
 */
public record AdminLoginResult(
        AdminLoginResponse response,
        String accessToken,
        String refreshToken,
        long refreshTokenValiditySeconds
) {

    /*
     * 두 토큰의 원문은 여기서만 잠깐 존재한다(컨트롤러가 쿠키로 감싸는 순간 이 객체는 버려진다).
     * REST 응답으로 나가지 않는다고 해서 로그에서도 안전한 건 아니므로 둘 다 가린다 (SEC-4-02).
     */
    @Override
    public String toString() {
        return "AdminLoginResult[response=" + response + ", accessToken=****, refreshToken=****, refreshTokenValiditySeconds="
                + refreshTokenValiditySeconds + "]";
    }
}