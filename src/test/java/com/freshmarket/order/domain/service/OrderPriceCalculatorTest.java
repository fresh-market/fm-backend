package com.freshmarket.order.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.freshmarket.order.domain.OrderPriceCalculator;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderPriceCalculatorTest {

    @Test
    void 쿠폰_연동_전에는_할인_없이_고정_배송비를_더한다() {
        OrderPriceCalculator.OrderPrice price = OrderPriceCalculator.calculate(List.of(
                new OrderPriceCalculator.PriceItem(2_000, 2),
                new OrderPriceCalculator.PriceItem(3_000, 1)
        ));

        assertThat(price.productAmount()).isEqualTo(7_000);
        assertThat(price.discountAmount()).isZero();
        assertThat(price.shippingFee()).isEqualTo(3_000);
        assertThat(price.totalAmount()).isEqualTo(10_000);
    }

    @Test
    void 빈_주문은_계산할_수_없다() {
        assertThatIllegalArgumentException().isThrownBy(() -> OrderPriceCalculator.calculate(List.of()));
    }
}
