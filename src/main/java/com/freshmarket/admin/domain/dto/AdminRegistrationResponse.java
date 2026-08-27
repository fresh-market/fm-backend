package com.freshmarket.admin.domain.dto;

import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminRole;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "발급된 관리자 계정")
public record AdminRegistrationResponse(
        @Schema(description = "관리자 로그인 아이디", example = "admin.lee") String loginId,
        @Schema(description = "관리자 이름", example = "이관리") String name,
        @Schema(description = "관리자 권한", example = "ADMIN") AdminRole role
) {

    public static AdminRegistrationResponse from(Admin admin) {
        return new AdminRegistrationResponse(admin.getLoginId(), admin.getName(), admin.getRole());
    }
}