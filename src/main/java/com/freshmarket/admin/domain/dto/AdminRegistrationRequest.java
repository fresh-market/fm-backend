package com.freshmarket.admin.domain.dto;

import com.freshmarket.admin.domain.entity.AdminRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "관리자 계정 발급 요청")
public record AdminRegistrationRequest(

        @Schema(description = "관리자 로그인 아이디", example = "admin.lee")
        @NotBlank(message = "아이디를 입력해 주세요.")
        @Size(max = 50, message = "아이디는 50자를 넘을 수 없습니다.")
        String loginId,

        @Schema(description = "초기 비밀번호", example = "Freshman!2026")
        @NotBlank(message = "초기 비밀번호를 입력해 주세요.")
        @Size(min = 10, max = 72, message = "비밀번호는 10자 이상 72자 이하여야 합니다.")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$",
                message = "비밀번호는 영문 대문자, 소문자, 숫자, 특수문자를 모두 포함해야 합니다.")
        String initialPassword,

        @Schema(description = "관리자 이름", example = "이관리")
        @NotBlank(message = "이름을 입력해 주세요.")
        @Size(max = 50, message = "이름은 50자를 넘을 수 없습니다.")
        String name,

        @Schema(description = "관리자 권한", example = "ADMIN")
        @NotNull(message = "권한을 선택해 주세요.")
        AdminRole role
) {

    // record 기본 toString()을 재정의해 초기 비밀번호와 이름이 로그에 노출되지 않도록 마스킹한다.
    @Override
    public String toString() {
        return "AdminRegistrationRequest[loginId=" + loginId
                + ", initialPassword=****, name=****, role=" + role + "]";
    }
}