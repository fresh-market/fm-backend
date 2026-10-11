package com.freshmarket.payment.internal.service;

import com.freshmarket.payment.internal.entity.Payment;
import com.freshmarket.payment.internal.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * [2026-09-28 KST] 외부 PG 취소(환불) 호출이 실패한 뒤, 짧은 별도 트랜잭션으로 미해결 횟수만
 * 기록한다. PaymentReconciliationAttemptService와 같은 이유로 PaymentCancellationService와
 * 분리한다 — PG 호출 중에는 DB 잠금을 잡지 않아야 한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentRefundAttemptService {

    private final PaymentRepository paymentRepository;

    @Transactional
    public boolean recordUnresolvedAttempt(Long paymentId, int maxAttempts) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null) {
            return false;
        }
        return payment.recordUnresolvedRefundAttempt(maxAttempts);
    }
}
