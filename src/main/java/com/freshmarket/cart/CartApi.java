package com.freshmarket.cart;

import java.util.List;

// order 등 상위 도메인이 주문 대상 장바구니 항목을 확인하고 정리할 때 쓰는 공개 창구다.
public interface CartApi {

    CartCheckoutInfo getCheckoutItems(Long memberId, List<Long> cartItemIds);

    void removeCheckedOutItems(Long memberId, List<CartCheckoutItem> checkedOutItems);
}
