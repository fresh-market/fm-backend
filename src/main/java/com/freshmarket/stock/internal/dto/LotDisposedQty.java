package com.freshmarket.stock.internal.dto;

/*
 * 로트별 폐기 누계. 소진율 계산에서 폐기분을 빼기 위해 쓴다.
 *
 * 폐기는 available_qty 를 줄이지만 팔린 것이 아니다. 빼지 않으면 일부만 폐기된 로트가
 * "그만큼 팔린" 것으로 보여 소진율이 부풀고, 정작 안 팔리는 재고가 캠페인 대상에서 빠진다.
 * sum() 이 Long 을 주므로 그대로 받는다.
 */
public record LotDisposedQty(Long stockLotId, Long disposedQty) {
}
