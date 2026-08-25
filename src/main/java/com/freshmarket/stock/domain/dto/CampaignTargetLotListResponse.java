package com.freshmarket.stock.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

// 오늘의 캠페인 대상 로트 조회 응답. AdminLotListResponse 와 같은 래퍼 형태를 쓴다
public record CampaignTargetLotListResponse(
        @Schema(description = "기준일", example = "2026-08-25") LocalDate targetDate,
        @Schema(description = "대상 로트 목록") List<CampaignTargetLotResponse> targets
) {
}
