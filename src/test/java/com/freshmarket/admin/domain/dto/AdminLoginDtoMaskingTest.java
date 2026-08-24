package com.freshmarket.admin.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.freshmarket.admin.domain.entity.AdminRole;
import org.junit.jupiter.api.Test;

/*
 * 로그인 요청은 비밀번호를, 내부 결과 객체는 access/refresh 토큰 원문을 잠깐 담는다.
 * 이 객체들이 로그에 그대로 찍혀도 비밀값이 평문으로 남지 않는지 확인한다 (SEC-4-02).
 */
class AdminLoginDtoMaskingTest {

    @Test
    void 요청의_toString은_비밀번호를_가린다() {
        AdminLoginRequest request = new AdminLoginRequest("admin.kim", "raw-password-1234");

        String result = request.toString();

        assertThat(result).contains("admin.kim").doesNotContain("raw-password-1234");
    }

    @Test
    void 결과의_toString은_액세스_토큰과_리프레시_토큰을_가린다() {
        AdminLoginResponse response = new AdminLoginResponse(
                1800L,
                new AdminLoginResponse.AdminSummary("admin.kim", "김관리", AdminRole.ADMIN));
        AdminLoginResult result = new AdminLoginResult(
                response,
                "raw-access-token-value",
                "raw-refresh-token-value",
                86400L);

        String output = result.toString();

        assertThat(output)
                .doesNotContain("raw-access-token-value")
                .doesNotContain("raw-refresh-token-value");
    }
}