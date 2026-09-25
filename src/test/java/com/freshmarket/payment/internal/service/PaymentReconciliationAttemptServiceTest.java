package com.freshmarket.payment.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.internal.entity.Payment;
import com.freshmarket.payment.internal.repository.PaymentRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationAttemptServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Test
    void 세번째_미해결_대사는_결제를_격리한다() {
        Payment payment = Payment.prepare(100L, PaymentMethod.CARD, 25_800);
        payment.markUnknown();
        ReflectionTestUtils.setField(payment, "id", 10L);
        ReflectionTestUtils.setField(payment, "reconciliationAttemptCount", 2);
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));
        PaymentReconciliationAttemptService sut = new PaymentReconciliationAttemptService(paymentRepository);

        boolean isolated = sut.recordUnresolvedAttempt(10L, 3);

        assertThat(isolated).isTrue();
        assertThat(payment.isReconciliationIsolated()).isTrue();
        assertThat(payment.getReconciliationAttemptCount()).isEqualTo(3);
    }

    @Test
    void 첫번째_미해결_대사는_다음_주기에_재시도한다() {
        Payment payment = Payment.prepare(100L, PaymentMethod.CARD, 25_800);
        payment.markUnknown();
        ReflectionTestUtils.setField(payment, "id", 10L);
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));
        PaymentReconciliationAttemptService sut = new PaymentReconciliationAttemptService(paymentRepository);

        boolean isolated = sut.recordUnresolvedAttempt(10L, 3);

        assertThat(isolated).isFalse();
        assertThat(payment.isReconciliationIsolated()).isFalse();
        assertThat(payment.getReconciliationAttemptCount()).isEqualTo(1);
    }

    @Test
    void 이미_확정된_결제는_대사_횟수를_늘리지_않는다() {
        Payment payment = Payment.prepare(100L, PaymentMethod.CARD, 25_800);
        payment.fail();
        ReflectionTestUtils.setField(payment, "id", 10L);
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));
        PaymentReconciliationAttemptService sut = new PaymentReconciliationAttemptService(paymentRepository);

        assertThat(sut.recordUnresolvedAttempt(10L, 3)).isFalse();
        assertThat(payment.getReconciliationAttemptCount()).isZero();
    }

    @Test
    void 없는_결제는_대사_격리_처리하지_않는다() {
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());
        PaymentReconciliationAttemptService sut = new PaymentReconciliationAttemptService(paymentRepository);

        assertThat(sut.recordUnresolvedAttempt(10L, 3)).isFalse();
    }
}
