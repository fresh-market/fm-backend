package com.freshmarket.order.internal.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * [2026-09-06 KST] PAYMENT_PENDING 만료를 주기적으로 훑는 스케줄러 어댑터다.
 * PaymentReconciliationScheduler와 같은 이유로 스케줄러 어댑터는 internal.service가 아니라
 * internal.batch에 둔다. 빈 자체를 batch 프로필로 묶는다 — @EnableScheduling만 꺼도 빈은 남아
 * 실수로 호출될 수 있다. 실행/소요시간 로그와 배치 신선도 지표는 SchedulerLoggingAspect가
 * @Scheduled 메서드마다 자동으로 남긴다.
 *
 * 유예 시간이 기본 60분(order.payment-expiration.grace-minutes)으로 결제 쪽 배치(UNKNOWN 5분,
 * PENDING 30분)보다 길다 — 이 배치가 다루는 PAYMENT_PENDING은 애초에 PG에 물어볼 거래 자체가
 * 없을 수도 있는 경우라 성급히 취소할수록 정상 결제와 부딪힐 여지만 늘어난다. 5분 주기까지는
 * 필요 없어서 10분으로 둔다.
 */
@Component
@Profile("batch")
@RequiredArgsConstructor
public class PendingOrderExpirationScheduler {

    private final PendingOrderExpirationService pendingOrderExpirationService;

    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
    public void expirePendingOrders() {
        pendingOrderExpirationService.expirePendingOrders();
    }
}
