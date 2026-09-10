package com.freshmarket.order.internal.batch;

import com.freshmarket.order.internal.service.OrderPaymentRequestOutboxDispatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("batch")
@RequiredArgsConstructor
public class OrderPaymentRequestOutboxScheduler {

    private final OrderPaymentRequestOutboxDispatchService outboxDispatchService;

    @Scheduled(cron = "0 */1 * * * *", zone = "Asia/Seoul")
    public void dispatchPendingPaymentRequests() {
        outboxDispatchService.dispatchPending();
    }
}
