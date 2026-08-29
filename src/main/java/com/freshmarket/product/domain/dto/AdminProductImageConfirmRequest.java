package com.freshmarket.product.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

// 업로드 완료 통지 요청. uploadId로 어느 업로드 세션을 확정하는지 증명한다(product_image.upload_id 스키마 주석 참고)
public record AdminProductImageConfirmRequest(
        @Schema(description = "업로드 URL 발급 시 받은 uploadId. REQUIRED") @NotNull UUID uploadId
) {
}
