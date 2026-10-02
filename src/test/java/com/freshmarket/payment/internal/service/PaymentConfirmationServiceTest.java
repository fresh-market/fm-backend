package com.freshmarket.payment.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.PaymentStatus;
import com.freshmarket.payment.internal.client.PaymentGateway;
import com.freshmarket.payment.internal.client.PaymentGatewayApproval;
import com.freshmarket.payment.internal.client.exception.PaymentGatewayRejectedException;
import com.freshmarket.payment.internal.client.exception.PaymentGatewayUnknownException;
import com.freshmarket.payment.internal.entity.Payment;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * [2026-09-27 KST] 짧은 DB 트랜잭션(PaymentService.beginConfirm/approvePayment/failPayment/
 * markPaymentUnknown)과 외부 PG 호출(PaymentGateway.confirm)의 경계를 조립하는 책임을 예전
 * PaymentApiImplTest에서 이쪽으로 옮겼다 — 그 조립 자체가 PaymentApiImpl에서 이 클래스로 옮겨왔기
 * 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentConfirmationServiceTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentResultOutboxDispatchService paymentResultOutboxDispatchService;

    @Mock
    private PaymentGateway paymentGateway;

    private PaymentConfirmationService sut;

    @BeforeEach
    void setUp() {
        sut = new PaymentConfirmationService(paymentService, paymentResultOutboxDispatchService, paymentGateway);
    }

    @Test
    void PG_승인_결과로_확정하고_outbox를_전달한다() {
        Payment payment = payment(10L);
        PaymentGatewayApproval approval = new PaymentGatewayApproval("pg_123",
                LocalDateTime.of(2026, 9, 6, 10, 0), PaymentMethod.CARD);
        PaymentResult approved = new PaymentResult(10L, 1L, PaymentMethod.CARD, 25_800,
                PaymentStatus.PAID, "pg_123", LocalDateTime.of(2026, 9, 6, 10, 0));
        when(paymentService.beginConfirm(1L, 7L, 25_800, "payment_key_1")).thenReturn(payment);
        when(paymentGateway.confirm("payment_key_1", payment.getPgOrderNo(), 25_800)).thenReturn(approval);
        when(paymentService.approvePayment(10L, approval)).thenReturn(approved);

        PaymentResult result = sut.confirm(1L, 7L, 25_800, "payment_key_1");

        assertThat(result).isEqualTo(approved);
        verify(paymentResultOutboxDispatchService).dispatchForPayment(10L);
    }

    @Test
    void 승인_반영_뒤_결과_outbox_전달이_실패해도_PAID를_UNKNOWN으로_바꾸지_않는다() {
        Payment payment = payment(10L);
        PaymentGatewayApproval approval = new PaymentGatewayApproval("pg_123",
                LocalDateTime.of(2026, 9, 6, 10, 0), PaymentMethod.CARD);
        PaymentResult approved = new PaymentResult(10L, 1L, PaymentMethod.CARD, 25_800,
                PaymentStatus.PAID, "pg_123", LocalDateTime.of(2026, 9, 6, 10, 0));
        when(paymentService.beginConfirm(1L, 7L, 25_800, "payment_key_1")).thenReturn(payment);
        when(paymentGateway.confirm("payment_key_1", payment.getPgOrderNo(), 25_800)).thenReturn(approval);
        when(paymentService.approvePayment(10L, approval)).thenReturn(approved);
        RuntimeException dispatchFailure = new RuntimeException("outbox repository unavailable");
        org.mockito.Mockito.doThrow(dispatchFailure)
                .when(paymentResultOutboxDispatchService).dispatchForPayment(10L);

        assertThatThrownBy(() -> sut.confirm(1L, 7L, 25_800, "payment_key_1"))
                .isSameAs(dispatchFailure);

        verify(paymentService, never()).markPaymentUnknown(any(), any());
    }

    @Test
    void PG가_거절하면_실패처리하고_outbox를_전달한다() {
        Payment payment = payment(10L);
        when(paymentService.beginConfirm(1L, 7L, 25_800, "payment_key_1")).thenReturn(payment);
        when(paymentGateway.confirm("payment_key_1", payment.getPgOrderNo(), 25_800))
                .thenThrow(new PaymentGatewayRejectedException("카드 한도 초과"));
        PaymentResult failed = new PaymentResult(10L, 1L, PaymentMethod.CARD, 25_800,
                PaymentStatus.FAILED, null, null);
        when(paymentService.failPayment(10L, "카드 한도 초과")).thenReturn(failed);

        PaymentResult result = sut.confirm(1L, 7L, 25_800, "payment_key_1");

        assertThat(result).isEqualTo(failed);
        verify(paymentResultOutboxDispatchService).dispatchForPayment(10L);
    }

    @Test
    void PG_응답이_불확실하면_UNKNOWN으로_남기고_outbox를_전달하지_않는다() {
        Payment payment = payment(10L);
        when(paymentService.beginConfirm(1L, 7L, 25_800, "payment_key_1")).thenReturn(payment);
        when(paymentGateway.confirm("payment_key_1", payment.getPgOrderNo(), 25_800))
                .thenThrow(new PaymentGatewayUnknownException("PG 응답 timeout", null));
        PaymentResult unknown = new PaymentResult(10L, 1L, PaymentMethod.CARD, 25_800,
                PaymentStatus.UNKNOWN, null, null);
        when(paymentService.markPaymentUnknown(10L, "PG 응답 timeout")).thenReturn(unknown);

        PaymentResult result = sut.confirm(1L, 7L, 25_800, "payment_key_1");

        assertThat(result).isEqualTo(unknown);
        verify(paymentResultOutboxDispatchService, never()).dispatchForPayment(any());
    }

    @Test
    void 이미_확정된_결제는_PG를_다시_부르지_않는다() {
        Payment payment = payment(10L);
        payment.approve("mock_123", LocalDateTime.of(2026, 8, 21, 15, 30), PaymentMethod.CARD);
        when(paymentService.beginConfirm(1L, 7L, 25_800, "payment_key_1")).thenReturn(payment);

        PaymentResult result = sut.confirm(1L, 7L, 25_800, "payment_key_1");

        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        verify(paymentGateway, never()).confirm(any(), any(), anyInt());
        verify(paymentResultOutboxDispatchService, never()).dispatchForPayment(any());
    }

    private Payment payment(Long id) {
        Payment payment = Payment.prepare(1L, 7L, PaymentMethod.CARD, 25_800);
        ReflectionTestUtils.setField(payment, "id", id);
        return payment;
    }
}
