package com.freshmarket.admin.domain.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.dto.AdminTokenResponse;
import com.freshmarket.admin.domain.service.AdminTokenService;
import com.freshmarket.common.auth.AuthCookieFactory;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.response.ResponseEnvelope;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

class AdminTokenControllerTest {

    @Test
    void 재발급에_성공하면_새_access_refresh_cookie를_내린다() {
        AdminTokenService tokenService = mock(AdminTokenService.class);
        JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
        when(jwtTokenProvider.getAccessTokenValidityMs()).thenReturn(1_800_000L);
        AuthCookieFactory cookieFactory = new AuthCookieFactory(jwtTokenProvider);
        ReflectionTestUtils.setField(cookieFactory, "secure", true);
        AdminTokenController controller = new AdminTokenController(tokenService, cookieFactory);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("refreshToken", "old-rt")});
        when(tokenService.reissue("old-rt")).thenReturn(
                new AdminTokenService.ReissueResult("new-at", 1800L, "new-rt", 86400L));

        ResponseEntity<ResponseEnvelope<AdminTokenResponse>> result = controller.reissue(request, response);

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().data().expiresInSeconds()).isEqualTo(1800L);

        ArgumentCaptor<String> cookieCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(response, org.mockito.Mockito.times(2))
                .addHeader(org.mockito.ArgumentMatchers.eq(HttpHeaders.SET_COOKIE), cookieCaptor.capture());
        List<String> cookies = cookieCaptor.getAllValues();
        assertThat(cookies).anySatisfy(c -> assertThat(c)
                .contains("refreshToken=new-rt")
                .contains("Path=/v1/admin/auth/")
                .contains("HttpOnly")
                .contains("Max-Age=86400"));
        assertThat(cookies).anySatisfy(c -> assertThat(c)
                .contains("accessToken=new-at")
                .contains("Path=/")
                .contains("HttpOnly"));
    }
}