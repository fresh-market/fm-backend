package com.freshmarket.cart.domain;

import com.freshmarket.cart.CartApi;
import com.freshmarket.cart.CartCheckoutInfo;
import com.freshmarket.cart.CartCheckoutItem;
import com.freshmarket.cart.domain.service.CartService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 구현체는 도메인 안에 감춰 다른 도메인이 CartService를 직접 참조하지 않게 한다.
@Component
@RequiredArgsConstructor
class CartApiImpl implements CartApi {

    private final CartService cartService;

    @Override
    public CartCheckoutInfo getCheckoutItems(Long memberId, List<Long> cartItemIds) {
        return cartService.getCheckoutItems(memberId, cartItemIds);
    }

    @Override
    public void removeCheckedOutItems(Long memberId, List<CartCheckoutItem> checkedOutItems) {
        cartService.removeCheckedOutItems(memberId, checkedOutItems);
    }
}
