package com.freshmarket.stock.domain.dto;

import com.freshmarket.stock.domain.entity.StockLot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// 상품의 로트별 조회 응답. stock.md의 "로트별 조회" 형태(lots 래퍼)를 그대로 쓴다
public record AdminLotListResponse(
        @Schema(description = "로트 목록") List<AdminLotResponse> lots
) {

    public static AdminLotListResponse of(List<StockLot> stockLots) {
        return new AdminLotListResponse(stockLots.stream().map(AdminLotResponse::of).toList());
    }
}
