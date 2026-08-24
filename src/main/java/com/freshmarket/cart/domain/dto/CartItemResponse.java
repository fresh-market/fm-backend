package com.freshmarket.cart.domain.dto;

import com.freshmarket.cart.domain.entity.CartItem;
import com.freshmarket.product.ProductOptionInfo;

public record CartItemResponse(
        Long cartItemId,
        Long productOptionId,
        String productName,
        String optionName,
        int price,
        int qty,
        boolean purchasable
) {

    public static CartItemResponse from(CartItem cartItem, ProductOptionInfo option) {
        return new CartItemResponse(
                cartItem.getId(), cartItem.getProductOptionId(), option.productName(), option.optionName(),
                option.price(), cartItem.getQty(), option.purchasable());
    }
}
