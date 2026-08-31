package com.freshmarket.admin.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 토큰 재발급 응답")
public record AdminTokenResponse(
        @Schema(description = "새 액세스 토큰 유효기간(초)", example = "1800")
        long expiresInSeconds
) {
}