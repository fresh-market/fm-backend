package com.freshmarket.order.internal;

import java.util.List;

/** coupon 연동 전 주문 금액 계산. 배송비 정책은 현재 모든 주문에 3,000원으로 고정한다. */
public final class OrderPriceCalculator {

    public static final int SHIPPING_FEE = 3_000;

    private OrderPriceCalculator() {
    }

    public static OrderPrice calculate(List<PriceItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("주문 항목은 한 개 이상이어야 합니다.");
        }

        int productAmount = items.stream()
                .mapToInt(PriceItem::lineAmount)
                .reduce(0, Math::addExact);

        // TODO: coupon 도메인 연동 후 주문/상품 쿠폰 할인 금액을 계산한다.
        int discountAmount = 0;
        return new OrderPrice(productAmount, discountAmount, SHIPPING_FEE,
                Math.subtractExact(Math.addExact(productAmount, SHIPPING_FEE), discountAmount));
    }

    public record PriceItem(int unitPrice, int qty) {
        public PriceItem {
            if (unitPrice < 0 || qty <= 0) {
                throw new IllegalArgumentException("주문 항목 가격과 수량이 올바르지 않습니다.");
            }
        }

        int lineAmount() {
            return Math.multiplyExact(unitPrice, qty);
        }
    }

    public record OrderPrice(int productAmount, int discountAmount, int shippingFee, int totalAmount) {
        public OrderPrice {
            if (productAmount < 0 || discountAmount < 0 || discountAmount > productAmount
                    || shippingFee < 0 || totalAmount != productAmount - discountAmount + shippingFee) {
                throw new IllegalArgumentException("주문 금액 구성이 올바르지 않습니다.");
            }
        }
    }
}
