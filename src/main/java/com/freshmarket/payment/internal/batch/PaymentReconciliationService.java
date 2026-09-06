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
 *
 * [2026-09-06 KST] PENDING도 이 배치의 대상으로 확장했다. markUnknown()은 PG 호출이 명확히
 * timeout·연결 유실 예외를 던졌을 때만 진입하는데, 그 예외조차 못 던지는 경우(프로세스가 PG 응답을
 * 기다리다 그대로 죽는 등)에는 결제가 UNKNOWN으로도 못 넘어가고 PENDING에 그대로 멈춘다 — 이런
 * 행은 UNKNOWN 전용 재확인만으로는 영영 걸러지지 않는다. TRANSFERRING 같은 새 상태를 만드는 대신,
 * 이미 있는 이 재확인 루프가 PENDING도 같은 방식(PG 조회 → approve/fail)으로 훑도록 넓히는 쪽을
 * 택했다 — reconcileOne 이하 로직은 UNKNOWN이든 PENDING이든 완전히 동일하다(PaymentService의
 * approvePayment/failPayment가 이미 두 상태를 다 받아준다).
 *
 * 다만 유예 시간은 UNKNOWN과 분리해서 훨씬 길게 잡는다(기본 30분, PENDING 쪽은
 * payment.reconciliation.pending-grace-minutes) — PENDING은 정상 흐름에서도 PG 응답을 기다리는
 * 동안 몇 초에서 길게는 수십 초까지 늘 거치는 상태라, UNKNOWN과 같은 짧은 유예를 쓰면 아직 응답을
 * 기다리는 중인 정상 결제까지 오탐으로 건드리게 된다.
 */
@Slf4j
@Service
public class PaymentReconciliationService {

    private static final int PAGE_SIZE = 100;

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentService paymentService;
    private final Clock clock;
    private final Duration unknownGracePeriod;
    private final Duration pendingGracePeriod;

    public PaymentReconciliationService(PaymentRepository paymentRepository, PaymentGateway paymentGateway,
            PaymentService paymentService, Clock clock,
            @Value("${payment.reconciliation.grace-minutes:5}") long unknownGraceMinutes,
            @Value("${payment.reconciliation.pending-grace-minutes:30}") long pendingGraceMinutes) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.paymentService = paymentService;
        this.clock = clock;
        this.unknownGracePeriod = Duration.ofMinutes(unknownGraceMinutes);
        this.pendingGracePeriod = Duration.ofMinutes(pendingGraceMinutes);
    }

    public void reconcileUnknownPayments() {
        reconcileByStatus(PaymentStatus.UNKNOWN, unknownGracePeriod);
    }

    /*
     * [2026-09-06 KST] PG 호출 도중 프로세스가 죽는 등 markUnknown()조차 거치지 못하고 PENDING에
     * 멈춘 결제를 걸러낸다. 유예 시간을 UNKNOWN보다 훨씬 길게 두는 이유는 클래스 주석 참고.
     */
    public void reconcilePendingPayments() {
        reconcileByStatus(PaymentStatus.PENDING, pendingGracePeriod);
    }

    private void reconcileByStatus(PaymentStatus status, Duration gracePeriod) {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(gracePeriod);
        Long afterId = 0L;
        List<Payment> page;
        Pageable pageable = PageRequest.of(0, PAGE_SIZE);
        do {
            page = paymentRepository.findByStatusAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc(
                    status, afterId, cutoff, pageable);
            for (Payment payment : page) {
                reconcileOne(payment);
                afterId = payment.getId();
            }
        } while (!page.isEmpty());
    }

    /*
     * 한 건의 PG 조회 또는 확정 실패가 이번 주기의 나머지 대상까지 멈추지 않는다. 실패한 행은
     * 상태와 updatedAt을 그대로 유지하므로 다음 주기에 다시 대상이 된다
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

        try {
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
                        "event=PAYMENT_RECONCILIATION_STILL_UNRESOLVED paymentId={} orderId={} status={}",
                        payment.getId(), payment.getOrderId(), payment.getStatus());
            }
        } catch (RuntimeException e) {
            /*
             * 한 결제의 DB 상태 전이 또는 결과 outbox 저장이 실패해도 그 행만 다음 주기에 재시도한다.
             * 바깥 커서 루프까지 예외를 전파하면 뒤에 있는 결제도 이번 주기에 확인하지 못한다.
             */
            log.error("event=PAYMENT_RECONCILIATION_RESOLVE_FAILED paymentId={} orderId={}",
                    payment.getId(), payment.getOrderId(), e);
        }
    }
}
