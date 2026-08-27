package com.freshmarket.admin.domain.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.dto.AdminRegistrationRequest;
import com.freshmarket.admin.domain.dto.AdminRegistrationResponse;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.service.AdminRegistrationService;
import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.common.response.ResponseEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AdminRegistrationControllerTest {

    private final AdminRegistrationService service = mock(AdminRegistrationService.class);
    private final AdminRegistrationController controller = new AdminRegistrationController(service);

    @Test
    void 최고관리자_정보와_발급_요청을_서비스에_전달하고_201을_반환한다() {
        CustomUserDetails issuer = new CustomUserDetails(1L, TokenType.ADMIN, "ROLE_SUPER_ADMIN");
        AdminRegistrationRequest request = new AdminRegistrationRequest(
                "admin.lee", "Freshman!2026", "이관리", AdminRole.ADMIN);
        AdminRegistrationResponse registered = new AdminRegistrationResponse("admin.lee", "이관리", "ADMIN");
        when(service.register(issuer.getId(), issuer.getRole(), request)).thenReturn(registered);

        ResponseEntity<ResponseEnvelope<AdminRegistrationResponse>> response = controller.register(issuer, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(registered);
    }
}