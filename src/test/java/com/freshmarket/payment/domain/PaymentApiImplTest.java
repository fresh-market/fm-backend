package com.freshmarket.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.freshmarket.payment.PaymentInfo;
import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.PaymentStatus;
import com.freshmarket.payment.domain.client.PaymentGateway;
import com.freshmarket.payment.domain.entity.Payment;
import com.freshmarket.payment.domain.service.PaymentService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentApiImplTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentGateway paymentGateway;

    @Test
    void 내부_결제_엔티티를_공개_조회_계약으로_변환한다() {
        Payment payment = Payment.prepare(1L, PaymentMethod.CARD, 25_800);
        ReflectionTestUtils.setField(payment, "id", 10L);
        payment.approve("mock_123", LocalDateTime.of(2026, 8, 21, 15, 30));
        when(paymentService.findPayment(1L)).thenReturn(Optional.of(payment));
        PaymentApiImpl sut = new PaymentApiImpl(paymentService, paymentGateway);

        Optional<PaymentInfo> result = sut.findPaymentInfo(1L);

        assertThat(result).contains(new PaymentInfo(10L, PaymentMethod.CARD, 25_800,
                PaymentStatus.PAID, LocalDateTime.of(2026, 8, 21, 15, 30)));
    }
}
