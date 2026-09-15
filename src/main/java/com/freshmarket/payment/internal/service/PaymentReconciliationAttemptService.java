package com.freshmarket.payment.internal.service;

import com.freshmarket.payment.internal.entity.Payment;
import com.freshmarket.payment.internal.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 외부 PG 조회가 끝난 뒤, 짧은 별도 트랜잭션으로 미해결 횟수만 기록한다. PG 호출 중에는 DB 잠금을
 * 잡지 않아야 하므로 PaymentReconciliationService와 분리한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentReconciliationAttemptService {

    private final PaymentRepository paymentRepository;

    @Transactional
    public boolean recordUnresolvedAttempt(Long paymentId, int maxAttempts) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null) {
            return false;
        }
        return payment.recordUnresolvedReconciliationAttempt(maxAttempts);
    }
}
