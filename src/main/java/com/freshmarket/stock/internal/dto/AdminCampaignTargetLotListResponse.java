package com.freshmarket.stock.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/*
 * 오늘의 캠페인 대상 로트 조회 응답. AdminLotListResponse 와 같은 래퍼 형태를 쓴다.
 * 모든 필드는 서버가 채우는 출력 전용(OUTPUT_ONLY)이다 — 배치가 확정한 값을 읽기만 한다 (API-4-14).
 */
public record AdminCampaignTargetLotListResponse(
        @Schema(description = "기준일 (OUTPUT_ONLY)", example = "2026-08-25") LocalDate targetDate,
        @Schema(description = "대상 로트 목록 (OUTPUT_ONLY)") List<AdminCampaignTargetLotResponse> targets,
        @Schema(description = "다음 페이지 토큰. 마지막 페이지면 null (OUTPUT_ONLY)") String nextPageToken
) {
}
