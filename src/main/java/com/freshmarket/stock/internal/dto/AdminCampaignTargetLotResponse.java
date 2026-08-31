package com.freshmarket.stock.internal.dto;

import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.internal.entity.CampaignTargetLot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/*
 * 캠페인 대상 로트 하나. stock.md 는 원래 회원에게 수치를 안 보여주지만, 이건 관리자 전용이라 그대로 노출한다.
 * 모든 필드는 서버가 채우는 출력 전용(OUTPUT_ONLY)이다 — 배치가 확정한 값을 읽기만 한다 (API-4-14).
 */
public record AdminCampaignTargetLotResponse(
        @Schema(description = "로트 ID (OUTPUT_ONLY)", example = "77") Long stockLotId,
        @Schema(description = "상품 ID (OUTPUT_ONLY)", example = "12") Long productId,
        @Schema(description = "상품명 (OUTPUT_ONLY)", example = "제주 감귤 1kg") String productName,
        @Schema(description = "옵션 ID (OUTPUT_ONLY)", example = "31") Long productOptionId,
        @Schema(description = "옵션명 (OUTPUT_ONLY)", example = "1kg") String optionName,
        @Schema(description = "소진율 (OUTPUT_ONLY)", example = "0.0500") BigDecimal turnoverRate,
        @Schema(description = "발급 가능 수량 (OUTPUT_ONLY)", example = "50") int issuableQty,
        @Schema(description = "순위(1이 가장 낮은 소진율) (OUTPUT_ONLY)", example = "1") int targetRank
) {
    public static AdminCampaignTargetLotResponse of(CampaignTargetLot lot, ProductOptionInfo info) {
        return new AdminCampaignTargetLotResponse(
                lot.getStockLotId(), info.productId(), info.productName(),
                info.productOptionId(), info.optionName(),
                lot.getTurnoverRate(), lot.getIssuableQty(), lot.getTargetRank());
    }
}
