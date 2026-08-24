package com.freshmarket.admin.domain.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.dto.AdminAccountIssueRequest;
import com.freshmarket.admin.domain.dto.AdminAccountIssueResponse;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.service.AdminAccountService;
import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.common.response.ResponseEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AdminAccountControllerTest {

    private final AdminAccountService adminAccountService = mock(AdminAccountService.class);
    private final AdminAccountController controller = new AdminAccountController(adminAccountService);

    @Test
    void 최고관리자가_계정을_발급하면_201과_임시비밀번호를_반환한다() {
        CustomUserDetails userDetails =
                new CustomUserDetails(1L, TokenType.ADMIN, "ROLE_SUPER_ADMIN");
        AdminAccountIssueRequest request =
                new AdminAccountIssueRequest("admin.lee", "이관리", AdminRole.ADMIN);
        AdminAccountIssueResponse issued =
                new AdminAccountIssueResponse("admin.lee", "이관리", AdminRole.ADMIN, "Abcd1234!Temp567");

        when(adminAccountService.issue(1L, "ROLE_SUPER_ADMIN", "admin.lee", "이관리", AdminRole.ADMIN))
                .thenReturn(issued);

        ResponseEntity<ResponseEnvelope<AdminAccountIssueResponse>> response =
                controller.issue(userDetails, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(issued);
        assertThat(issued.toString()).doesNotContain("Abcd1234!Temp567");
        verify(adminAccountService).issue(1L, "ROLE_SUPER_ADMIN", "admin.lee", "이관리", AdminRole.ADMIN);
    }
}