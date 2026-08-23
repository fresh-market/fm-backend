package com.freshmarket.payment.domain.service;

import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.PaymentInfo;
import com.freshmarket.payment.PaymentApprovedEvent;
import com.freshmarket.payment.domain.PaymentPreparation;
import com.freshmarket.payment.domain.client.PaymentGatewayApproval;
import com.freshmarket.payment.domain.entity.Payment;
import com.freshmarket.payment.domain.exception.PaymentErrorCode;
import com.freshmarket.payment.domain.exception.PaymentException;
import com.freshmarket.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.Optional;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // PG 호출 전에 PENDING 행을 별도 트랜잭션으로 확정한다. 외부 호출 동안 DB 트랜잭션을 잡지 않는다.
    @Transactional
    public PaymentPreparation preparePayment(PaymentRequest request) {
        validateRequest(request);
        boolean newlyPrepared = paymentRepository.insertIfAbsent(request.orderId(), request.method().name(),
                request.amount(), LocalDateTime.now(clock)) == 1;
        Payment payment = paymentRepository.findByOrderId(request.orderId())
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (!payment.matches(request)) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_MISMATCH);
        }
        return new PaymentPreparation(payment, newlyPrepared);
    }

    @Transactional
    public PaymentResult approvePayment(Long paymentId, PaymentGatewayApproval approval) {
        if (paymentId == null || paymentId <= 0 || approval == null
                || approval.pgTid() == null || approval.paidAt() == null) {
            throw new PaymentException(PaymentErrorCode.INVALID_PAYMENT_APPROVAL);
        }
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (payment.isPaid()) {
            return PaymentResult.from(payment);
        }
        if (!payment.isPending()) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_PENDING);
        }

        payment.approve(approval.pgTid(), approval.paidAt());

        log.info("event=PAYMENT_PAID paymentId={} orderId={} amount={} method={}",
                payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getMethod());
        eventPublisher.publishEvent(new PaymentApprovedEvent(
                payment.getId(), payment.getOrderId(), payment.getPaidAt()));

        return PaymentResult.from(payment);
    }

    public Optional<PaymentInfo> findPaymentInfo(Long orderId) {
        return paymentRepository.findByOrderId(orderId).map(PaymentInfo::from);
    }

    private void validateRequest(PaymentRequest request) {
        if (request == null || request.orderId() == null || request.orderId() <= 0
                || request.amount() <= 0 || request.method() == null) {
            throw new PaymentException(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
        }
    }
}
