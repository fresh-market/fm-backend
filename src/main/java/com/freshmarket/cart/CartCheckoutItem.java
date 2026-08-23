package com.freshmarket.cart;

// 주문 생성 시점의 상품 옵션 정보다. order는 이 값으로 주문 상품 스냅샷과 금액을 만든다.
public record CartCheckoutItem(
        Long cartItemId,
        Long productOptionId,
        String productName,
        String optionName,
        int unitPrice,
        int qty
) {
}
