package com.freshmarket.admin.domain.dto;

import com.freshmarket.admin.domain.entity.AdminRole;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 계정 발급 응답")
public record AdminAccountIssueResponse(
        @Schema(description = "관리자 로그인 아이디", example = "admin.lee")
        String loginId,

        @Schema(description = "관리자 이름", example = "이관리")
        String name,

        @Schema(description = "관리자 권한", example = "ADMIN")
        AdminRole role,

        @Schema(description = "최초 로그인에 사용할 임시 비밀번호")
        String temporaryPassword
) {
    // 임시 비밀번호가 예외 로그나 디버그 로그에 노출되지 않도록 record 기본 toString()을 가린다.
    @Override
    public String toString() {
        return "AdminAccountIssueResponse[loginId=" + loginId
                + ", name=" + name
                + ", role=" + role
                + ", temporaryPassword=****]";
    }
}