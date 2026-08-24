package com.freshmarket.stock.domain.dto;

import com.freshmarket.stock.domain.entity.StockLot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// 만료 로트 일괄 처리 응답. 이번 호출로 실제 전환된 로트만 담는다
public record AdminLotExpireResponse(
        @Schema(description = "이번에 만료 처리된 로트 목록") List<AdminLotResponse> lots
) {

    public static AdminLotExpireResponse of(List<StockLot> expiredLots) {
        return new AdminLotExpireResponse(expiredLots.stream().map(AdminLotResponse::of).toList());
    }
}
