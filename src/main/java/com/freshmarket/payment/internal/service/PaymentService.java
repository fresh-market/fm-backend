package com.freshmarket.payment.internal.service;

import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.internal.PaymentPreparation;
import com.freshmarket.payment.internal.client.PaymentGatewayApproval;
import com.freshmarket.payment.internal.entity.Payment;
import com.freshmarket.payment.internal.entity.PaymentResultOutbox;
import com.freshmarket.payment.internal.exception.PaymentErrorCode;
import com.freshmarket.payment.internal.exception.PaymentException;
import com.freshmarket.payment.internal.repository.PaymentRepository;
import com.freshmarket.payment.internal.repository.PaymentResultOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.Optional;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentResultOutboxRepository paymentResultOutboxRepository;
    private final Clock clock;

    /*
     * [2026-09-27 KST] 예전에는 여기서 곧바로 PG 승인 요청까지 이어졌지만, 토스는 프론트가 결제창에서
     * 결제수단 선택·인증까지 마친 뒤에야 서버가 confirm을 부를 수 있는 구조라 이 메서드는 이제 PENDING
     * 행을 만드는 데서 끝난다. 실제 승인 시도는 별도로 호출되는 beginConfirm()/confirm API가 맡는다.
     * "PG 호출 전에 PENDING 행을 별도 트랜잭션으로 확정한다"는 원래 의도(외부 호출 동안 DB 트랜잭션을
     * 잡지 않는다)는 여전히 유효하다 — 다만 그 외부 호출이 이제 이 메서드 밖, 훨씬 나중에 일어난다.
     *
     * pgOrderNo는 삽입 전에 미리 계산해 네이티브 upsert에 함께 실어야 한다(Payment.pgOrderNoFor 참고).
     */
    @Transactional
    public PaymentPreparation preparePayment(PaymentRequest request) {
        validateRequest(request);
        String pgOrderNo = Payment.pgOrderNoFor(request.orderId());
        boolean newlyPrepared = paymentRepository.insertIfAbsent(request.orderId(), pgOrderNo, request.memberId(),
                request.method().name(), request.amount(), LocalDateTime.now(clock)) == 1;
        Payment payment = paymentRepository.findByOrderId(request.orderId())
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (!payment.matches(request)) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_MISMATCH);
        }
        return new PaymentPreparation(payment, newlyPrepared);
    }

    /*
     * [2026-09-27 KST] confirm API가 실제로 토스 confirm을 부르기 "직전"에 짧은 트랜잭션으로 부른다.
     * 세 가지를 이 한 트랜잭션 안에서 검증한다 — 본인 주문인지(memberId), 금액이 맞는지(토스에 보낼
     * 금액을 신뢰할 수 있는지), 같은 결제에 다른 paymentKey로 confirm이 이미 진행 중이지는 않은지.
     * 검증을 통과하면 recordConfirmAttempt()로 paymentKey를 미리 반영해, 이후 confirm이 성공하든
     * 실패하든 이 결제가 "confirm이 실제로 시도됐다"는 사실이 남는다 — 복구 배치가 이탈과 구분할 때
     * 쓴다(Payment.recordConfirmAttempt 클래스 주석 참고).
     *
     * findByIdForUpdate 대신 findByOrderIdForUpdate를 쓴다 — 이 시점의 호출자는 order_id만 안다.
     */
    @Transactional
    public Payment beginConfirm(Long orderId, Long memberId, int amount, String paymentKey) {
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (!payment.getMemberId().equals(memberId)) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_FORBIDDEN);
        }
        if (payment.getAmount() != amount) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_MISMATCH);
        }
        if (payment.hasConflictingConfirmAttempt(paymentKey)) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_CONFIRM_IN_PROGRESS);
        }
        payment.recordConfirmAttempt(paymentKey);
        return payment;
    }

    /*
     * [2026-09-05 19:13 KST] PENDING의 최초 승인뿐 아니라, 복구 배치가 UNKNOWN을 뒤늦게 PAID로
     * 확정할 때도 이 메서드를 그대로 쓴다 — 그래서 "대기 상태가 아니면 거부"하는 조건에 UNKNOWN도
     * 통과시키도록 넓혔다. Payment.approve() 쪽 가드도 같은 이유로 함께 넓어졌다.
     *
     * order 전달 의도도 여기서 outbox로 저장한다. Payment 상태와 같은 트랜잭션에 남기므로, 프로세스가
     * 커밋 직후 죽어도 dispatcher가 나중에 order에 다시 전달할 수 있다.
     */
    @Transactional
    public PaymentResult approvePayment(Long paymentId, PaymentGatewayApproval approval) {
        if (paymentId == null || paymentId <= 0 || approval == null
                || approval.pgTid() == null || approval.paidAt() == null || approval.method() == null) {
            throw new PaymentException(PaymentErrorCode.INVALID_PAYMENT_APPROVAL);
        }
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (payment.isPaid()) {
            return PaymentResult.from(payment);
        }
        if (!payment.isPending() && !payment.isUnknown()) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_PENDING);
        }

        payment.approve(approval.pgTid(), approval.paidAt(), approval.method());

        log.info("event=PAYMENT_PAID paymentId={} orderId={} amount={} method={}",
                payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getMethod());
        paymentResultOutboxRepository.save(
                PaymentResultOutbox.approved(payment.getId(), payment.getOrderId(), payment.getPaidAt()));

        return PaymentResult.from(payment);
    }

    /*
     * [2026-09-05 19:13 KST] PG가 명확히 거절했을 때, 그리고 내부 반영 실패 후 복구 배치가 PG
     * 재조회로 거절을 확인했을 때 모두 이 메서드로 온다. 이미 FAILED면 그대로 반환해 재시도로 인한
     * 중복 처리를 막는다 — PAID처럼 findByIdForUpdate로 잠근 뒤 판단하므로 동시 호출에도 안전하다.
     *
     * 실패 결과 outbox도 실제로 FAILED로 전이할 때만(이미 FAILED였던 경우는 제외) 저장한다.
     * UNKNOWN 상태에서는 이 메서드가 호출되지 않으므로 — 아직 PG 결과가 확정 안 된 결제 때문에
     * 주문을 섣불리 취소하는 일은 없다.
     */
    @Transactional
    public PaymentResult failPayment(Long paymentId, String reason) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (payment.isFailed()) {
            return PaymentResult.from(payment);
        }
        payment.fail();
        log.info("event=PAYMENT_FAILED paymentId={} orderId={} amount={} method={} reason={}",
                payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getMethod(), reason);
        paymentResultOutboxRepository.save(PaymentResultOutbox.failed(payment.getId(), payment.getOrderId(), reason));
        return PaymentResult.from(payment);
    }

    /*
     * [2026-09-05 17:54 KST] PG 응답을 알 수 없을 때(timeout·연결 유실, 또는 내부 반영 자체가
     * 실패한 경우) 호출한다. 복구 배치가 PG 거래 조회로 PAID/FAILED를 확정하기 전까지의 중간
     * 상태다. order에게는 아무 것도 알리지 않는다 — 아직 결론이 안 났으니 주문을 건드릴 수 없다.
     */
    @Transactional
    public PaymentResult markPaymentUnknown(Long paymentId, String reason) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (payment.isUnknown()) {
            return PaymentResult.from(payment);
        }
        payment.markUnknown();
        log.info("event=PAYMENT_UNKNOWN paymentId={} orderId={} amount={} method={} reason={}",
                payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getMethod(), reason);
        return PaymentResult.from(payment);
    }

    public Optional<Payment> findPayment(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }

    private void validateRequest(PaymentRequest request) {
        if (request == null || request.orderId() == null || request.orderId() <= 0
                || request.memberId() == null || request.memberId() <= 0
                || request.amount() <= 0 || request.method() == null) {
            throw new PaymentException(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
        }
    }
}
