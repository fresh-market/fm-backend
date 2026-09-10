package com.freshmarket.payment.internal.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.PaymentStatus;
import com.freshmarket.payment.internal.client.PaymentGateway;
import com.freshmarket.payment.internal.client.PaymentGatewayInquiryResult;
import com.freshmarket.payment.internal.entity.Payment;
import com.freshmarket.payment.internal.repository.PaymentRepository;
import com.freshmarket.payment.internal.service.PaymentService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentService paymentService;

    @Test
    void 한_결제의_확정_실패가_뒤_결제의_재확인을_막지_않는다() {
        Payment first = unknownPayment(10L, 100L);
        Payment second = unknownPayment(20L, 200L);
        LocalDateTime paidAt = LocalDateTime.of(2026, 9, 6, 10, 0);
        when(paymentRepository.findByStatusAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc(
                eq(PaymentStatus.UNKNOWN), anyLong(), any(), any()))
                .thenReturn(List.of(first, second), List.of());
        when(paymentGateway.inquire(100L)).thenReturn(PaymentGatewayInquiryResult.approved("pg_100", paidAt));
        when(paymentGateway.inquire(200L)).thenReturn(PaymentGatewayInquiryResult.rejected("카드 거절"));
        when(paymentService.approvePayment(eq(10L), any()))
                .thenThrow(new RuntimeException("outbox write failed"));
        when(paymentService.failPayment(20L, "카드 거절"))
                .thenReturn(new PaymentResult(20L, 200L, PaymentMethod.CARD, 25_800,
                        PaymentStatus.FAILED, null, null));
        PaymentReconciliationService sut = new PaymentReconciliationService(paymentRepository, paymentGateway,
                paymentService, Clock.fixed(Instant.parse("2026-09-06T11:00:00Z"), ZoneOffset.UTC), 5, 30);

        sut.reconcileUnknownPayments();

        verify(paymentService).failPayment(20L, "카드 거절");
    }

    private static Payment unknownPayment(Long paymentId, Long orderId) {
        Payment payment = Payment.prepare(orderId, PaymentMethod.CARD, 25_800);
        payment.markUnknown();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        return payment;
    }
}
