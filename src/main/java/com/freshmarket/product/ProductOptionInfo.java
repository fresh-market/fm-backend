package com.freshmarket.product;

// 다른 도메인이 참조하는 상품 옵션 정보. product + product_option 을 join 해 만든다
public record ProductOptionInfo(
        Long productOptionId,
        String productName,
        String optionName,
        int price,
        boolean purchasable
) {
}