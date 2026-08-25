package com.freshmarket.stock.domain.dto;

import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.domain.entity.CampaignTargetLot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

// 캠페인 대상 로트 하나. stock.md 는 원래 회원에게 수치를 안 보여주지만, 이건 관리자 전용이라 그대로 노출한다
public record CampaignTargetLotResponse(
        @Schema(description = "로트 ID", example = "77") Long stockLotId,
        @Schema(description = "상품 ID", example = "12") Long productId,
        @Schema(description = "상품명", example = "제주 감귤 1kg") String productName,
        @Schema(description = "옵션 ID", example = "31") Long productOptionId,
        @Schema(description = "옵션명", example = "1kg") String optionName,
        @Schema(description = "소진율", example = "0.0500") BigDecimal turnoverRate,
        @Schema(description = "발급 가능 수량", example = "50") int issuableQty,
        @Schema(description = "순위(1이 가장 낮은 소진율)", example = "1") int targetRank
) {
    public static CampaignTargetLotResponse of(CampaignTargetLot lot, ProductOptionInfo info) {
        return new CampaignTargetLotResponse(
                lot.getStockLotId(), info.productId(), info.productName(),
                info.productOptionId(), info.optionName(),
                lot.getTurnoverRate(), lot.getIssuableQty(), lot.getTargetRank());
    }
}
