package com.freshmarket.cart.domain;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.freshmarket.cart.CartCheckoutCompletedEvent;
import com.freshmarket.cart.CartCheckoutItem;
import com.freshmarket.cart.domain.service.CartService;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CartCheckoutCleanupListenerTest {

    @Mock
    private CartService cartService;

    private CartCheckoutCleanupListener sut;

    @Test
    void 커밋후_이벤트를_받으면_체크아웃한_항목을_장바구니에서_지운다() {
        sut = new CartCheckoutCleanupListener(cartService);
        List<CartCheckoutItem> items = List.of(new CartCheckoutItem(1L, 20L, "감귤 1kg", "1kg", 12_900, 2));
        CartCheckoutCompletedEvent event = new CartCheckoutCompletedEvent(1L, items);

        sut.handle(event);

        verify(cartService).removeCheckedOutItems(1L, items);
    }

    @Test
    void 장바구니_정리가_실패해도_예외를_다시_던지지_않는다() {
        sut = new CartCheckoutCleanupListener(cartService);
        List<CartCheckoutItem> items = List.of(new CartCheckoutItem(1L, 20L, "감귤 1kg", "1kg", 12_900, 2));
        CartCheckoutCompletedEvent event = new CartCheckoutCompletedEvent(1L, items);
        doThrow(new IllegalStateException("cart gone")).when(cartService).removeCheckedOutItems(1L, items);

        // 이미 커밋된 주문 요청 스레드로 예외가 전파돼 500이 되면 안 된다 — 그냥 삼키고 로그만 남긴다.
        Assertions.assertThatCode(() -> sut.handle(event)).doesNotThrowAnyException();
    }
}
