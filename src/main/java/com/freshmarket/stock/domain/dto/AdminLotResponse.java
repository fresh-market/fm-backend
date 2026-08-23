package com.freshmarket.stock.domain.dto;

import com.freshmarket.stock.domain.entity.StockLot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

// 로트 입고 등록 응답. stock.md의 로트별 조회 응답과 같은 형태를 쓴다
public record AdminLotResponse(
        @Schema(description = "로트 ID", example = "77") Long stockLotId,
        @Schema(description = "옵션 ID", example = "31") Long productOptionId,
        @Schema(description = "입고일", example = "2026-08-17") LocalDate receivedDate,
        @Schema(description = "소비기한", example = "2026-08-31") LocalDate expiryDate,
        @Schema(description = "입고 수량", example = "200") int initialQty,
        @Schema(description = "판매 가능 수량", example = "200") int availableQty,
        @Schema(description = "로트 상태", example = "AVAILABLE") String status
) {

    public static AdminLotResponse of(StockLot stockLot) {
        return new AdminLotResponse(stockLot.getId(), stockLot.getProductOptionId(), stockLot.getReceivedDate(),
                stockLot.getExpiryDate(), stockLot.getInitialQty(), stockLot.getAvailableQty(),
                stockLot.getStatus().name());
    }
}
