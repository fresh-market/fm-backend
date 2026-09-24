package com.freshmarket.order.internal;

import com.freshmarket.order.internal.dto.OrderCreateResponse;

/*
 * OrderPendingCreationService(짧은 트랜잭션)와 OrderCreateService(트랜잭션 밖에서 결제 이벤트를
 * 발행하는 조립부) 사이의 내부 전달값이다. newlyCreated가 false면 requestId 재시도로 기존 주문을
 * 그대로 돌려준 것이라 — 호출부는 결제 요청 이벤트를 다시 발행하지 않는다.
 *
 * internal.service 패키지 밖(internal 바로 아래)에 두는 이유: ArchitectureTest의 서비스_이름 규칙이
 * domain.service의 모든 최상위 클래스에 Service 접미사를 요구한다 — 이 record는 서비스가 아니라
 * 서비스 사이의 내부 전달값이라 그 접미사를 붙일 수 없다. domain.service를 넘나들며 쓰이므로
 * package-private 대신 public으로 둔다.
 */
public record PendingOrderResult(OrderCreateResponse response, boolean newlyCreated) {
}
