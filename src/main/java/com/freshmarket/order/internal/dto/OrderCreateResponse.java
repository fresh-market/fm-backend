package com.freshmarket.order.internal.dto;

import com.freshmarket.common.pg.MerchantOrderNoGenerator;
import com.freshmarket.order.internal.entity.Order;
import com.freshmarket.order.internal.entity.OrderStatus;

/*
 * 주문 접수 직후 응답. 결제 및 재고 확정 결과는 후속 상태 조회로 확인한다.
 *
 * [2026-09-27 KST] pgOrderNo를 추가했다 — 프론트가 토스 결제창을 여는 데 필요한 가맹점 주문번호다.
 * orderNo(우리 쪽 표시용 주문번호)는 토스 orderId 규칙(6~64자)을 만족 못할 수 있어 그대로 못
 * 쓴다(MerchantOrderNoGenerator 클래스 주석 참고). order와 payment 둘 다 L2라 서로의 타입을 가져다
 * 쓸 수 없어 payment에게 물어볼 수도 없는데, 이 값은 orderId만으로 결정되는 순수 계산이라 order가
 * 여기서 독립적으로 같은 값을 낼 수 있다 — payment는 이 값을 자기 Payment 행(pg_order_no)에
 * 스냅샷해 저장해두고, 나중에 확정(confirm) API가 그 스냅샷을 신뢰한다.
 */
public record OrderCreateResponse(Long orderId, String orderNo, String pgOrderNo, OrderStatus status, int totalAmount) {

    public static OrderCreateResponse from(Order order) {
        return new OrderCreateResponse(order.getId(), order.getOrderNo(),
                MerchantOrderNoGenerator.from(order.getId()), order.getStatus(), order.getTotalAmount());
    }
}
