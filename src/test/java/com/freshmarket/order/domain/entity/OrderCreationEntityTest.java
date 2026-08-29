package com.freshmarket.order.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class OrderCreationEntityTest {

    @Test
    void 요청_식별자를_포함해_결제대기_주문을_생성한다() {
        Order order = Order.place(placement());

        assertThat(order.getRequestId()).isEqualTo("request-1");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getDiscountAmount()).isZero();
    }

    @Test
    void 결제대기_주문만_결제완료로_전이한다() {
        Order order = Order.place(placement());

        order.markPaid();
        order.markPaid();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void 취소된_주문은_결제완료로_전이할_수_없다() {
        Order order = Order.place(placement());
        order.cancel();

        assertThatIllegalStateException().isThrownBy(order::markPaid);
    }

    @Test
    void 요청_해시는_sha256_16진수여야_한다() {
        OrderPlacement invalid = new OrderPlacement("request-1", "invalid", 1L, "ORD-1", 10_000,
                0, 3_000, 13_000, "수령인", "01012345678", "12345", "서울시", null,
                LocalDateTime.of(2026, 8, 23, 10, 0));

        assertThatIllegalArgumentException().isThrownBy(() -> Order.place(invalid));
    }

    @Test
    void 주문생성_항목은_cart_원본과_ORDERED_상태를_보관한다() {
        OrderItem item = OrderItem.place(itemPlacement());

        assertThat(item.getSourceCartItemId()).isEqualTo(11L);
        assertThat(item.getItemStatus()).isEqualTo(OrderItemStatus.ORDERED);
    }

    @Test
    void 주문상품_할인은_상품금액을_넘을_수_없다() {
        OrderItemPlacement invalid = new OrderItemPlacement(1L, 11L, 100L, "상품", "옵션", 1_000,
                2, 1L, 2_001);

        assertThatIllegalArgumentException().isThrownBy(() -> OrderItem.place(invalid));
    }

    private OrderPlacement placement() {
        return new OrderPlacement("request-1", "a".repeat(64), 1L, "ORD-1", 10_000,
                0, 3_000, 13_000, "수령인", "01012345678", "12345", "서울시", null,
                LocalDateTime.of(2026, 8, 23, 10, 0));
    }

    private OrderItemPlacement itemPlacement() {
        return new OrderItemPlacement(1L, 11L, 100L, "상품", "옵션", 1_000, 2, 1L, 0);
    }
}
