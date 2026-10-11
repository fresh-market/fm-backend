package com.freshmarket.payment.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.internal.entity.Payment;
import com.freshmarket.payment.internal.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentRefundAttemptServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Test
    void 세번째_미해결_환불은_결제를_격리한다() {
        Payment payment = paidPayment();
        ReflectionTestUtils.setField(payment, "id", 10L);
        ReflectionTestUtils.setField(payment, "refundAttemptCount", 2);
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));
        PaymentRefundAttemptService sut = new PaymentRefundAttemptService(paymentRepository);

        boolean isolated = sut.recordUnresolvedAttempt(10L, 3);

        assertThat(isolated).isTrue();
        assertThat(payment.isRefundIsolated()).isTrue();
        assertThat(payment.getRefundAttemptCount()).isEqualTo(3);
    }

    @Test
    void 첫번째_미해결_환불은_다음_주기에_재시도한다() {
        Payment payment = paidPayment();
        ReflectionTestUtils.setField(payment, "id", 10L);
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));
        PaymentRefundAttemptService sut = new PaymentRefundAttemptService(paymentRepository);

        boolean isolated = sut.recordUnresolvedAttempt(10L, 3);

        assertThat(isolated).isFalse();
        assertThat(payment.isRefundIsolated()).isFalse();
        assertThat(payment.getRefundAttemptCount()).isEqualTo(1);
    }

    @Test
    void PAID가_아니면_환불_시도_횟수를_늘리지_않는다() {
        Payment payment = Payment.prepare(100L, 7L, PaymentMethod.CARD, 25_800);
        ReflectionTestUtils.setField(payment, "id", 10L);
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));
        PaymentRefundAttemptService sut = new PaymentRefundAttemptService(paymentRepository);

        assertThat(sut.recordUnresolvedAttempt(10L, 3)).isFalse();
        assertThat(payment.getRefundAttemptCount()).isZero();
    }

    @Test
    void 없는_결제는_환불_격리_처리하지_않는다() {
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());
        PaymentRefundAttemptService sut = new PaymentRefundAttemptService(paymentRepository);

        assertThat(sut.recordUnresolvedAttempt(10L, 3)).isFalse();
    }

    private Payment paidPayment() {
        Payment payment = Payment.prepare(100L, 7L, PaymentMethod.CARD, 25_800);
        payment.approve("toss_key_1", LocalDateTime.of(2026, 8, 21, 15, 30), PaymentMethod.CARD);
        return payment;
    }
}
