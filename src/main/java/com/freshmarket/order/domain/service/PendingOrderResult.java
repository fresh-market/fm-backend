package com.freshmarket.order.domain.service;

import com.freshmarket.order.domain.dto.OrderCreateResponse;

/*
 * OrderPendingCreationService(짧은 트랜잭션)와 OrderCreateService(트랜잭션 밖에서 결제 이벤트를
 * 발행하는 조립부) 사이의 내부 전달값이다. newlyCreated가 false면 requestId 재시도로 기존 주문을
 * 그대로 돌려준 것이라 — 호출부는 결제 요청 이벤트를 다시 발행하지 않는다.
 */
record PendingOrderResult(OrderCreateResponse response, boolean newlyCreated) {
}
