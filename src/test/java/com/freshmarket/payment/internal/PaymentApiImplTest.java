package com.freshmarket.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.payment.PaymentInfo;
import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.PaymentStatus;
import com.freshmarket.payment.internal.client.PaymentGateway;
import com.freshmarket.payment.internal.client.PaymentGatewayApproval;
import com.freshmarket.payment.internal.entity.Payment;
import com.freshmarket.payment.internal.service.PaymentService;
import com.freshmarket.payment.internal.service.PaymentResultOutboxDispatchService;
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

    @Mock
    private PaymentResultOutboxDispatchService paymentResultOutboxDispatchService;

    @Test
    void 내부_결제_엔티티를_공개_조회_계약으로_변환한다() {
        Payment payment = Payment.prepare(1L, PaymentMethod.CARD, 25_800);
        ReflectionTestUtils.setField(payment, "id", 10L);
        payment.approve("mock_123", LocalDateTime.of(2026, 8, 21, 15, 30));
        when(paymentService.findPayment(1L)).thenReturn(Optional.of(payment));
        PaymentApiImpl sut = new PaymentApiImpl(paymentService, paymentResultOutboxDispatchService, paymentGateway);

        Optional<PaymentInfo> result = sut.findPaymentInfo(1L);

        assertThat(result).contains(new PaymentInfo(10L, PaymentMethod.CARD, 25_800,
                PaymentStatus.PAID, LocalDateTime.of(2026, 8, 21, 15, 30)));
    }

    @Test
    void 승인_반영_뒤_결과_outbox_전달이_실패해도_PAID를_UNKNOWN으로_바꾸지_않는다() {
        Payment payment = Payment.prepare(1L, PaymentMethod.CARD, 25_800);
        ReflectionTestUtils.setField(payment, "id", 10L);
        PaymentGatewayApproval approval = new PaymentGatewayApproval("pg_123",
                LocalDateTime.of(2026, 9, 6, 10, 0));
        PaymentResult approved = new PaymentResult(10L, 1L, PaymentMethod.CARD, 25_800,
                PaymentStatus.PAID, "pg_123", LocalDateTime.of(2026, 9, 6, 10, 0));
        when(paymentService.preparePayment(new PaymentRequest(1L, 25_800, PaymentMethod.CARD)))
                .thenReturn(new PaymentPreparation(payment, true));
        when(paymentGateway.request(any())).thenReturn(approval);
        when(paymentService.approvePayment(10L, approval)).thenReturn(approved);
        RuntimeException dispatchFailure = new RuntimeException("outbox repository unavailable");
        org.mockito.Mockito.doThrow(dispatchFailure)
                .when(paymentResultOutboxDispatchService).dispatchForPayment(10L);
        PaymentApiImpl sut = new PaymentApiImpl(paymentService, paymentResultOutboxDispatchService, paymentGateway);

        assertThatThrownBy(() -> sut.requestPayment(new PaymentRequest(1L, 25_800, PaymentMethod.CARD)))
                .isSameAs(dispatchFailure);

        verify(paymentService, never()).markPaymentUnknown(any(), any());
    }
}
