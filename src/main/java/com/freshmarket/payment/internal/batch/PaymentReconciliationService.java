package com.freshmarket.payment.internal.batch;

import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.PaymentStatus;
import com.freshmarket.payment.internal.client.PaymentGateway;
import com.freshmarket.payment.internal.client.PaymentGatewayApproval;
import com.freshmarket.payment.internal.client.PaymentGatewayInquiryResult;
import com.freshmarket.payment.internal.entity.Payment;
import com.freshmarket.payment.internal.repository.PaymentRepository;
import com.freshmarket.payment.internal.service.PaymentService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/*
 * [2026-09-05 18:28 KST] UNKNOWN으로 남은 결제를 PG 거래 조회(inquire)로 재확인해 PAID/FAILED로
 * 확정한다. PendingProductImageCleanupService와 같은 이유로 internal.service 패키지(커버리지 100%
 * 대상)가 아니라 batch 패키지에 둔다 — 스케줄러가 부르는 배치 어댑터라 일반 서비스와는 성격이 다르다.
 *
 * 실제 상태 전이(승인/실패 확정)는 PaymentService.approvePayment/failPayment를 그대로 재사용한다.
 * findByIdForUpdate로 잠그고, 이미 같은 상태면 그대로 반환하는 멱등 처리가 거기 이미 있어서, 여기서
 * 락이나 중복 처리를 따로 구현할 필요가 없다.
 *
 * 대상 조회(findByStatusAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc)는 잠그지 않은 평범한
 * 조회다 — 그 사이 값이 바뀌어도 실제 확정은 PaymentService 쪽에서 다시 잠그고 확인하므로 안전하다.
 */
@Slf4j
@Service
public class PaymentReconciliationService {

    private static final int PAGE_SIZE = 100;

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentService paymentService;
    private final Clock clock;
    private final Duration gracePeriod;

    public PaymentReconciliationService(PaymentRepository paymentRepository, PaymentGateway paymentGateway,
            PaymentService paymentService, Clock clock,
            @Value("${payment.reconciliation.grace-minutes:5}") long graceMinutes) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.paymentService = paymentService;
        this.clock = clock;
        this.gracePeriod = Duration.ofMinutes(graceMinutes);
    }

    public void reconcileUnknownPayments() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(gracePeriod);
        Long afterId = 0L;
        List<Payment> page;
        Pageable pageable = PageRequest.of(0, PAGE_SIZE);
        do {
            page = paymentRepository.findByStatusAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc(
                    PaymentStatus.UNKNOWN, afterId, cutoff, pageable);
            for (Payment payment : page) {
                reconcileOne(payment);
                afterId = payment.getId();
            }
        } while (!page.isEmpty());
    }

    /*
     * 한 건의 조회 실패(PG 조회 API 자체가 응답하지 않는 경우 등)가 이번 주기의 나머지 대상까지
     * 멈추지 않는다 — 실패한 행은 상태와 updatedAt을 그대로 유지하므로 다음 주기에 다시 대상이 된다
     * (PendingProductImageCleanupService.cleanupOne과 같은 이유).
     */
    private void reconcileOne(Payment payment) {
        PaymentGatewayInquiryResult result;
        try {
            result = paymentGateway.inquire(payment.getOrderId());
        } catch (RuntimeException e) {
            log.warn("event=PAYMENT_RECONCILIATION_INQUIRE_FAILED paymentId={} orderId={}",
                    payment.getId(), payment.getOrderId(), e);
            return;
        }

        switch (result.status()) {
            case APPROVED -> {
                PaymentResult reconciled = paymentService.approvePayment(payment.getId(),
                        new PaymentGatewayApproval(result.pgTid(), result.paidAt()));
                log.info("event=PAYMENT_RECONCILIATION_RESOLVED paymentId={} orderId={} status={}",
                        payment.getId(), payment.getOrderId(), reconciled.status());
            }
            case REJECTED -> {
                PaymentResult reconciled = paymentService.failPayment(payment.getId(), result.reason());
                log.info("event=PAYMENT_RECONCILIATION_RESOLVED paymentId={} orderId={} status={}",
                        payment.getId(), payment.getOrderId(), reconciled.status());
            }
            case STILL_PROCESSING -> log.info(
                    "event=PAYMENT_RECONCILIATION_STILL_UNKNOWN paymentId={} orderId={}",
                    payment.getId(), payment.getOrderId());
        }
    }
}
