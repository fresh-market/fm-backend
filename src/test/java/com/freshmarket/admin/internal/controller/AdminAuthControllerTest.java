package com.freshmarket.admin.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.freshmarket.admin.internal.dto.AdminLoginRequest;
import com.freshmarket.admin.internal.dto.AdminLoginResponse;
import com.freshmarket.admin.internal.dto.AdminLoginResult;
import com.freshmarket.admin.internal.entity.AdminRole;
import com.freshmarket.admin.internal.service.AdminAuthService;
import com.freshmarket.common.auth.AuthCookieFactory;
import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.common.response.ResponseEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;

class AdminAuthControllerTest {

    private static final String REFRESH_TOKEN_COOKIE_PATH = "/v1/admin/auth/";
    private static final String ACCESS_TOKEN = "access-token";
    private static final String REFRESH_TOKEN = "refresh-token";
    private static final long ACCESS_TOKEN_VALIDITY_MS = 1800000L;
    private static final long ACCESS_TOKEN_VALIDITY_SECONDS = 1800L;
    private static final long REFRESH_TOKEN_VALIDITY_SECONDS = 86400L;

    private final AdminAuthService adminAuthService = mock(AdminAuthService.class);

    @Test
    void 로그인에_성공하면_액세스_토큰과_리프레시_토큰이_HttpOnly_쿠키로_내려간다() {
        // given
        JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
        when(jwtTokenProvider.getAccessTokenValidityMs()).thenReturn(ACCESS_TOKEN_VALIDITY_MS);

        AuthCookieFactory authCookieFactory = new AuthCookieFactory(jwtTokenProvider);
        ReflectionTestUtils.setField(authCookieFactory, "secure", true);
        AdminAuthController controller = new AdminAuthController(adminAuthService, authCookieFactory);

        AdminLoginRequest request = new AdminLoginRequest("admin.kim", "Freahman!2026");
        AdminLoginResponse loginResponse = new AdminLoginResponse(
                ACCESS_TOKEN_VALIDITY_SECONDS,
                new AdminLoginResponse.AdminSummary("admin.kim", "김관리", AdminRole.ADMIN)
        );

        when(adminAuthService.login(request)).thenReturn(new AdminLoginResult(
                loginResponse,
                ACCESS_TOKEN,
                REFRESH_TOKEN,
                REFRESH_TOKEN_VALIDITY_SECONDS
        ));

        // when
        ResponseEntity<ResponseEnvelope<AdminLoginResponse>> response = controller.login(request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(loginResponse);

        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).hasSize(2);

        String accessCookie = setCookies.stream()
                .filter(cookie -> cookie.startsWith("accessToken="))
                .findFirst()
                .orElseThrow();
        assertThat(accessCookie)
                .contains("accessToken=" + ACCESS_TOKEN)
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/")
                .contains("Max-Age=1800");

        String refreshCookie = setCookies.stream()
                .filter(cookie -> cookie.startsWith("refreshToken="))
                .findFirst()
                .orElseThrow();
        assertThat(refreshCookie)
                .contains("refreshToken=" + REFRESH_TOKEN)
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=" + REFRESH_TOKEN_COOKIE_PATH)
                .contains("Max-Age=86400");

        assertThat(response.getBody().data().toString())
                .doesNotContain(ACCESS_TOKEN)
                .doesNotContain(REFRESH_TOKEN);
    }

    @Test
    void 로그아웃하면_인증_쿠키를_둘_다_만료시킨다() {
        JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
        AuthCookieFactory authCookieFactory = new AuthCookieFactory(jwtTokenProvider);
        ReflectionTestUtils.setField(authCookieFactory, "secure", true);
        AdminAuthController controller = new AdminAuthController(adminAuthService, authCookieFactory);
        CustomUserDetails userDetails = new CustomUserDetails(1L, TokenType.ADMIN, "ROLE_ADMIN");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        ResponseEntity<Void> response = controller.logout(userDetails, servletResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(adminAuthService).logout(1L, "ROLE_ADMIN");

        Collection<String> setCookies = servletResponse.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).hasSize(2);
        assertThat(setCookies).anySatisfy(cookie -> assertThat(cookie)
                .contains("refreshToken=")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/v1/admin/auth/")
                .contains("Max-Age=0"));

        assertThat(setCookies).anySatisfy(cookie -> assertThat(cookie)
                .contains("accessToken=")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/")
                .contains("Max-Age=0"));
    }
}