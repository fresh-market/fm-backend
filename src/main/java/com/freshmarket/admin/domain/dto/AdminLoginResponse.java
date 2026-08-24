package com.freshmarket.admin.domain.dto;

import com.freshmarket.admin.domain.entity.AdminRole;
import io.swagger.v3.oas.annotations.media.Schema;

/*
 * accessToken/refreshToken은 이 응답에 담지 않는다. AdminAuthController가 둘 다 HttpOnly 쿠키로 내려준다.
 * JS가 읽을 수 있는 응답 본문에 토큰을 실으면 HttpOnly로 얻는 XSS 방어 효과가 없어진다.
 *
 * adminId는 담지 않는다 (IDS-7-01). 로그인 이후의 모든 인증된 요청은 JWT로 식별되고, 클라이언트가 adminId를 다시 넘겨줄 일이 없다.
 */
@Schema(description = "관리자 로그인 응답")
public record AdminLoginResponse(

        @Schema(description = "액세스 토큰 유효기간(초)", example = "1800")
        long expiresInSeconds,

        @Schema(description = "로그인한 관리자 요약 정보")
        AdminSummary admin
) {

    @Schema(description = "로그인한 관리자 요약 정보")
    public record AdminSummary(

            @Schema(description = "관리자 로그인 아이디", example = "admin.kim")
            String loginId,

            @Schema(description = "관리자 이름", example = "김관리")
            String name,

            @Schema(description = "관리자 권한")
            AdminRole role
    ) {
    }
}