package com.freshmarket.stock.internal.dto;

import com.freshmarket.stock.internal.entity.DisposalReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 로트 폐기 요청
public record AdminLotDisposeRequest(
        @Schema(description = "요청 식별자(클라이언트가 생성). 같은 값으로 재시도하면 최초 처리 결과를 그대로 돌려준다. REQUIRED",
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") @NotBlank @Size(max = 100) String requestId,
        @Schema(description = "폐기 수량. REQUIRED", example = "12") @NotNull @Min(1) @Max(100_000) Integer quantity,
        @Schema(description = "폐기 사유. REQUIRED") @NotNull DisposalReason disposalReason,
        @Schema(description = "메모. OPTIONAL", example = "소비기한 경과분") @Size(max = 200) String reason
) {
}
