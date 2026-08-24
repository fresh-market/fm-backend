package com.freshmarket.product.domain;

import com.freshmarket.product.OptionAvailabilityChangedEvent;
import com.freshmarket.product.domain.batch.OptionAvailabilitySyncRetryService;
import com.freshmarket.product.domain.service.ProductOptionAvailabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 이벤트 리스너 어댑터라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다.
@Slf4j
@Component
@RequiredArgsConstructor
public class OptionAvailabilityEventListener {

    private final ProductOptionAvailabilityService productOptionAvailabilityService;
    private final OptionAvailabilitySyncRetryService optionAvailabilitySyncRetryService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(OptionAvailabilityChangedEvent event) {
        try {
            productOptionAvailabilityService.updateSoldOut(event.productOptionId(), event.soldOut());
        } catch (Exception e) {
            // (DI-6-01) 여기서 다시 던지지 않는다 — AFTER_COMMIT 리스너의 예외는 원 요청 스레드로
            // 동기 전파돼, 이미 성공적으로 끝난 로트 입고 요청을 뒤늦게 500으로 만든다.
            log.warn("event=OPTION_AVAILABILITY_SYNC_FAILED productOptionId={} soldOut={} — 아웃박스에 기록, 스케줄러가 재시도",
                    event.productOptionId(), event.soldOut(), e);
            optionAvailabilitySyncRetryService.recordFailure(event.productOptionId(), event.soldOut());
        }
    }
}
