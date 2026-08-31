package com.freshmarket.admin.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminRegistrationRequestTest {

    @Test
    void toString은_초기비밀번호와_이름을_노출하지_않는다() {
        AdminRegistrationRequest request = new AdminRegistrationRequest(
                "admin.lee", "Freshman!2026", "이관리", "ADMIN");

        String result = request.toString();

        assertThat(result)
                .contains("loginId=admin.lee", "role=ADMIN")
                .contains("initialPassword=****", "name=****")
                .doesNotContain("Freshman!2026", "이관리");
    }
}