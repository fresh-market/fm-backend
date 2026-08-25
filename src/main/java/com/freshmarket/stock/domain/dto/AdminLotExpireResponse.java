package com.freshmarket.stock.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/*
 * 만료 로트 일괄 처리 응답. (PERF-4-03) 대상 전체를 응답에 실으면 청크로 나눠 처리한 취지(대량을
 * 한 번에 메모리에 올리지 않음)가 응답 조립 단계에서 무효화되므로, 개별 로트 목록 대신 처리 건수만
 * 담는다.
 */
public record AdminLotExpireResponse(
        @Schema(description = "이번 호출로 실제 전환된 로트 건수") int expiredCount
) {

    public static AdminLotExpireResponse of(int expiredCount) {
        return new AdminLotExpireResponse(expiredCount);
    }
}
