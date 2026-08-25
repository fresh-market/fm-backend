package com.freshmarket.cart;

import java.util.List;

/*
 * 장바구니 기반 주문이 커밋된 뒤에만 장바구니 정리를 하기 위한 이벤트.
 *
 * (2026-08-25) order 패키지가 아니라 여기(cart 루트)에 둔다 — ArchitectureTest의
 * 도메인은_아래로만_부른다 규칙상 cart(L1)는 order(L2)에 의존하면 안 되고, order(L2)가 cart(L1)에
 * 의존하는 것만 허용된다. 이벤트를 order 소유로 두면 cart의 리스너가 order 패키지를 import해야 해서
 * 그 규칙을 거꾸로 어긴다. cart가 자신의 공개 타입으로 이벤트를 선언하고, order는 이미 허용된
 * 방향으로 이 타입을 가져다 발행만 하면 방향이 깨지지 않는다 — CartApi.removeCheckedOutItems를
 * 비동기로 부르는 것과 같은 셈이라 의미상으로도 cart 소유가 자연스럽다.
 */
public record CartCheckoutCompletedEvent(Long memberId, List<CartCheckoutItem> cartItems) {
}
