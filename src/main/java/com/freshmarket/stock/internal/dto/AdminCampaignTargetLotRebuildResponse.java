package com.freshmarket.stock.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * 관리자 재확정 결과.
 *
 * <p>모든 필드는 서버가 채우는 출력 전용(OUTPUT_ONLY)이다 — 재확정이 만들어 낸 값을
 * 돌려주기만 한다 (API-4-14). 요청 본문이 없으므로 클라이언트가 보내는 값은 하나도 없다.
 *
 * <p>확정된 건수를 돌려주는 이유는, 0 이 나오는 것이 실패가 아니라 정상일 수 있어서다.
 * 후보가 없는 날은 대상도 없다. 호출한 관리자가 그 둘을 구분할 수 있어야 한다.
 *
 * @param targetDate     확정한 기준일
 * @param confirmedCount 확정된 대상 로트 수
 */
public record AdminCampaignTargetLotRebuildResponse(
        @Schema(description = "확정한 기준일 (OUTPUT_ONLY)", example = "2026-08-31") LocalDate targetDate,
        @Schema(description = "확정된 대상 로트 수 (OUTPUT_ONLY)", example = "12") int confirmedCount) {
}
