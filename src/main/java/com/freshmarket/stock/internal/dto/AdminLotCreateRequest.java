package com.freshmarket.stock.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

// 로트 입고 등록 요청. receivedDate를 생략하면 서비스가 오늘 날짜로 채운다
public record AdminLotCreateRequest(
        @Schema(description = "요청 식별자(클라이언트가 생성). 같은 값으로 재시도하면 최초 입고 결과를 그대로 돌려준다",
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") @NotBlank @Size(max = 100) String requestId,
        @Schema(description = "입고일. 생략하면 오늘", example = "2026-08-17") LocalDate receivedDate,
        @Schema(description = "소비기한", example = "2026-08-31") @NotNull LocalDate expiryDate,
        @Schema(description = "입고 수량", example = "200") @NotNull @Min(1) @Max(100_000) Integer initialQty
) {
}
