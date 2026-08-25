package com.freshmarket.cart.domain.dto;

import com.freshmarket.cart.domain.entity.Cart;
import java.util.List;

public record CartResponse(Long cartId, List<CartItemResponse> items, int totalQty) {

    public static CartResponse from(Cart cart, List<CartItemResponse> items) {
        int totalQty = items.stream().mapToInt(CartItemResponse::qty).sum();
        return new CartResponse(cart.getId(), items, totalQty);
    }
}
