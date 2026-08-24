package com.freshmarket.admin.domain.dto;

import com.freshmarket.admin.domain.entity.AdminRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "관리자 계정 발급 요청")
public record AdminAccountIssueRequest(
        @Schema(description = "관리자 로그인 아이디", example = "admin.lee")
        @NotBlank(message = "아이디를 입력해 주세요.")
        @Size(max = 50, message = "아이디는 50자를 넘을 수 없습니다.")
        String loginId,

        @Schema(description = "관리자 이름", example = "이관리")
        @NotBlank(message = "이름을 입력해 주세요.")
        @Size(max = 50, message = "이름은 50자를 넘을 수 없습니다.")
        String name,

        @Schema(description = "관리자 권한", example = "ADMIN")
        @NotNull(message = "권한을 입력해 주세요.")
        AdminRole role
) {
}