package com.freshmarket.product.domain;

import com.freshmarket.product.OptionAvailabilityChangedEvent;
import com.freshmarket.product.domain.service.ProductOptionAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 이벤트 리스너 어댑터라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다.
@Component
@RequiredArgsConstructor
public class OptionAvailabilityEventListener {

    private final ProductOptionAvailabilityService productOptionAvailabilityService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(OptionAvailabilityChangedEvent event) {
        productOptionAvailabilityService.updateSoldOut(event.productOptionId(), event.soldOut());
    }
}
