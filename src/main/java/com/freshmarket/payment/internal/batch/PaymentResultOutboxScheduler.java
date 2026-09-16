package com.freshmarket.payment.internal.batch;

import com.freshmarket.payment.internal.service.PaymentResultOutboxDispatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("batch")
@RequiredArgsConstructor
public class PaymentResultOutboxScheduler {

    private final PaymentResultOutboxDispatchService outboxDispatchService;

    @Scheduled(cron = "30 */1 * * * *", zone = "Asia/Seoul")
    public void dispatchPendingPaymentResults() {
        outboxDispatchService.dispatchPending();
    }
}
