package com.freshmarket.stock.domain.dto;

import com.freshmarket.stock.domain.entity.DisposalReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 로트 폐기 요청
public record AdminLotDisposeRequest(
        @Schema(description = "폐기 수량", example = "12") @NotNull @Min(1) @Max(100_000) Integer quantity,
        @Schema(description = "폐기 사유") @NotNull DisposalReason disposalReason,
        @Schema(description = "메모(선택)", example = "소비기한 경과분") @Size(max = 200) String reason
) {
}
