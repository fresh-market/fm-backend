package com.freshmarket.cart;

import java.util.List;

public record CartCheckoutInfo(
        Long cartId,
        List<CartCheckoutItem> items
) {
    public CartCheckoutInfo {
        items = List.copyOf(items);
    }
}
