package com.freshmarket.cart.domain;

import com.freshmarket.cart.CartCheckoutCompletedEvent;
import com.freshmarket.cart.domain.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 이벤트 리스너 어댑터라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다. */
@Slf4j
@Component
@RequiredArgsConstructor
class CartCheckoutCleanupListener {

    private final CartService cartService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(CartCheckoutCompletedEvent event) {
        try {
            cartService.removeCheckedOutItems(event.memberId(), event.cartItems());
        } catch (Exception e) {
            // (2026-08-25) 실패해도 주문은 이미 커밋된 뒤라 되돌릴 수 없고, 여기서 다시 던지면
            // AFTER_COMMIT 리스너 예외가 원 요청 스레드로 전파돼 이미 끝난 주문 요청이 500이 된다.
            // 실패 시 그냥 로그만 남긴다 — cart 정리는 주문 성공에 영향 없는 부수효과다.
            log.warn("event=CART_CHECKOUT_CLEANUP_FAILED memberId={}", event.memberId(), e);
        }
    }
}
