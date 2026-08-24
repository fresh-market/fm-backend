package com.freshmarket.admin.domain.controller;

import com.freshmarket.admin.domain.dto.AdminAccountIssueRequest;
import com.freshmarket.admin.domain.dto.AdminAccountIssueResponse;
import com.freshmarket.admin.domain.service.AdminAccountService;
import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 계정", description = "관리자 계정 발급")
@RestController
@RequestMapping("/v1/admin/admins")
class AdminAccountController {

    private final AdminAccountService adminAccountService;

    AdminAccountController(AdminAccountService adminAccountService) {
        this.adminAccountService = adminAccountService;
    }

    @Operation(
            summary = "관리자 계정 발급",
            description = "최고관리자가 새 관리자 계정과 최초 로그인용 임시 비밀번호를 발급한다.")
    @ApiResponse(responseCode = "201", description = "계정 발급 성공")
    @ApiResponse(responseCode = "403", description = "최고관리자 권한 필요 (ADMIN-005)")
    @ApiResponse(responseCode = "409", description = "로그인 아이디 중복 (ADMIN-006)")
    @PostMapping
    ResponseEntity<ResponseEnvelope<AdminAccountIssueResponse>> issue(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody AdminAccountIssueRequest request) {

        AdminAccountIssueResponse response = adminAccountService.issue(
                userDetails.getId(),
                userDetails.getRole(),
                request.loginId(),
                request.name(),
                request.role());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseEnvelope.success(response));
    }
}