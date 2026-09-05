package com.freshmarket.payment.internal.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * [2026-09-05 18:28 KST] UNKNOWN 결제를 주기적으로 재확인하는 스케줄러 어댑터다. 스케줄러 어댑터라
 * 서비스가 아니고, internal.service 패키지(커버리지 100% 대상)에 있으면 안 된다 —
 * PendingProductImageCleanupScheduler와 같은 이유. 실행/소요시간 로그와 배치 신선도 지표는
 * SchedulerLoggingAspect가 @Scheduled 메서드마다 자동으로 남긴다.
 *
 * 팀 방침(배치 사용 최소화)과 달리 5분마다 돈다 — 여기서 다루는 건 고아 파일이 아니라 사용자가
 * 결제했는지 알 수 없는 채로 멈춰있는 주문이라, 방치되는 시간 자체가 곧 사용자 경험/정합성 문제다.
 * UNKNOWN 유예 시간(기본 5분, payment.reconciliation.grace-minutes)보다 촘촘해야 유예가 끝나자마자
 * 다음 주기 안에 재확인이 실제로 일어난다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
public class PaymentReconciliationScheduler {

    private final PaymentReconciliationService paymentReconciliationService;

    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    public void reconcileUnknownPayments() {
        paymentReconciliationService.reconcileUnknownPayments();
    }
}
