package com.freshmarket.stock.internal.dto;

import com.freshmarket.stock.internal.entity.StockLot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// 상품의 로트별 조회 응답. stock.md의 "로트별 조회" 형태(lots 래퍼)를 그대로 쓴다 (API-3-04: 커서 페이지네이션)
public record AdminLotListResponse(
        @Schema(description = "로트 목록") List<AdminLotResponse> lots,
        @Schema(description = "다음 페이지 토큰. 마지막 페이지면 null") String nextPageToken
) {

    public static AdminLotListResponse of(List<StockLot> stockLots, String nextPageToken) {
        return new AdminLotListResponse(stockLots.stream().map(AdminLotResponse::of).toList(), nextPageToken);
    }
}
