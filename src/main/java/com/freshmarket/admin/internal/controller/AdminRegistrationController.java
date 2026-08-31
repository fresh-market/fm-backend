package com.freshmarket.admin.internal.controller;

import com.freshmarket.admin.internal.dto.AdminRegistrationRequest;
import com.freshmarket.admin.internal.dto.AdminRegistrationResponse;
import com.freshmarket.admin.internal.service.AdminRegistrationService;
import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 계정", description = "최고관리자가 직원용 관리자 계정을 발급한다")
@RestController
@RequestMapping("/v1/admin/admins")
@RequiredArgsConstructor
class AdminRegistrationController {

    private final AdminRegistrationService adminRegistrationService;

    @Operation(
            summary = "관리자 계정 발급",
            description = "최고관리자만 사용할 수 있다. 초기 비밀번호는 BCrypt 해시로만 저장한다.")
    @ApiResponse(responseCode = "201", description = "계정 발급 성공")
    @ApiResponse(responseCode = "403", description = "최고관리자 권한 필요 (ADMIN-005)")
    @ApiResponse(responseCode = "409", description = "로그인 아이디 중복 (ADMIN-006)")
    @PostMapping
    ResponseEntity<ResponseEnvelope<AdminRegistrationResponse>> register(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody AdminRegistrationRequest request) {
        AdminRegistrationResponse response = adminRegistrationService.register(
                userDetails.getId(), userDetails.getRole(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }
}