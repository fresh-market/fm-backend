package com.freshmarket.payment.internal.service;

import com.freshmarket.payment.internal.client.PaymentGateway;
import com.freshmarket.payment.internal.entity.Payment;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * [2026-09-27 KST] order가 이미 CANCELED로 확정한 주문에 뒤늦게 결제가 승인된 경우
 * (common.event.OrderPaymentRefundRequestedEvent) 실제 PG 취소(환불)를 조립한다.
 *
 * PaymentConfirmationService와 같은 이유로 이 클래스에도 @Transactional을 클래스/메서드 어디에도
 * 걸지 않는다 — 외부 PG 호출(paymentGateway.cancel) 동안 DB 트랜잭션을 잡지 않기 위해서다. 실제
 * 상태 전이는 PaymentService.beginCancel()/finishCancel()이 각자 자기 트랜잭션을 갖는다.
 *
 * 이 흐름은 사람이 개입해 재시도하는 별도 스케줄러를 두지 않는다 — 실패하면 예외가 그대로
 * OrderPaymentRefundRequestedEventListener를 거쳐 그 이벤트를 발행한 원래
 * OrderPaymentApprovedEvent 처리(OrderCreateService.onPaymentApproved)까지 전파되고, 그 이벤트를
 * 전달한 PaymentResultOutboxDispatchService.dispatchOne()이 outbox를 미완료로 남겨 다음 배치
 * 주기에 같은 승인 이벤트를 다시 전달한다 — 그러면 order가 여전히 CANCELED이므로 환불 요청 이벤트도
 * 다시 발행되어 이 취소가 자연스럽게 재시도된다.
 *
 * [2026-09-28 KST] 다만 이 재시도가 끝없이 반복되면 안 된다 — 취소 불가 금액처럼 영구적으로
 * 거절되는 경우, outbox가 매 배치 주기(PaymentResultOutboxScheduler, 1분 간격)마다 같은 PG 호출을
 * 반복하며 로그만 쌓이고 아무도 못 알아챈다. PaymentReconciliationService가 MAX_RECONCILIATION_
 * ATTEMPTS로 대사 재시도를 제한하는 것과 같은 이유로, 여기서도 MAX_REFUND_ATTEMPTS를 넘기면
 * PaymentRefundAttemptService가 Payment.refundIsolated를 세운다. 격리된 뒤에는 예외를 다시
 * 던지지 않고 조용히 끝낸다 — outbox가 dispatched로 확정되어 더 이상 재시도되지 않고, payment는
 * PAID로 order는 CANCELED로 남는 불일치 상태를 ERROR 로그 한 번으로 알린 뒤 운영자의 수동 개입을
 * 기다린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancellationService {

    private static final int MAX_REFUND_ATTEMPTS = 3;

    private final PaymentService paymentService;
    private final PaymentGateway paymentGateway;
    private final PaymentRefundAttemptService refundAttemptService;

    public void cancelForRefund(Long paymentId, String reason) {
        Optional<Payment> payment = paymentService.beginCancel(paymentId);
        if (payment.isEmpty()) {
            // PAID가 아니다 — 이미 취소가 끝났거나(이벤트 재전달) 애초에 승인된 적 없는 상태다.
            // 둘 다 PG를 다시 부를 필요가 없다.
            log.info("event=PAYMENT_CANCEL_SKIPPED_NOT_PAID paymentId={}", paymentId);
            return;
        }
        if (payment.get().isRefundIsolated()) {
            // 이미 MAX_REFUND_ATTEMPTS를 넘겨 격리된 결제다 — 자동 재시도를 포기했으므로 PG를
            // 다시 부르지 않는다. 운영자가 직접 확인해야 한다.
            log.error("event=PAYMENT_CANCEL_SKIPPED_ISOLATED paymentId={}", paymentId);
            return;
        }

        try {
            paymentGateway.cancel(payment.get().getPgTid(), reason);
        } catch (RuntimeException e) {
            boolean isolated = refundAttemptService.recordUnresolvedAttempt(paymentId, MAX_REFUND_ATTEMPTS);
            if (isolated) {
                log.error("event=PAYMENT_CANCEL_ISOLATED paymentId={} attempts={}",
                        paymentId, MAX_REFUND_ATTEMPTS, e);
                return;
            }
            log.warn("event=PAYMENT_CANCEL_RETRY_SCHEDULED paymentId={}", paymentId, e);
            throw e;
        }

        paymentService.finishCancel(paymentId, reason);
    }
}
