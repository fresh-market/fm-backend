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
import com.freshmarket.payment.internal.service.PaymentReconciliationAttemptService;
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

    @Mock
    private PaymentReconciliationAttemptService attemptService;

    @Test
    void 한_결제의_확정_실패가_뒤_결제의_재확인을_막지_않는다() {
        Payment first = unknownPayment(10L, 100L);
        Payment second = unknownPayment(20L, 200L);
        LocalDateTime paidAt = LocalDateTime.of(2026, 9, 6, 10, 0);
        when(paymentRepository.findByStatusAndReconciliationIsolatedFalseAndPgTidIsNotNullAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc(
                eq(PaymentStatus.UNKNOWN), anyLong(), any(), any()))
                .thenReturn(List.of(first, second), List.of());
        when(paymentGateway.inquire(first.getPgOrderNo()))
                .thenReturn(PaymentGatewayInquiryResult.approved("pg_100", paidAt, PaymentMethod.CARD));
        when(paymentGateway.inquire(second.getPgOrderNo())).thenReturn(PaymentGatewayInquiryResult.rejected("카드 거절"));
        when(paymentService.approvePayment(eq(10L), any()))
                .thenThrow(new RuntimeException("outbox write failed"));
        when(paymentService.failPayment(20L, "카드 거절"))
                .thenReturn(new PaymentResult(20L, 200L, PaymentMethod.CARD, 25_800,
                        PaymentStatus.FAILED, null, null));
        PaymentReconciliationService sut = new PaymentReconciliationService(paymentRepository, paymentGateway,
                paymentService, attemptService,
                Clock.fixed(Instant.parse("2026-09-06T11:00:00Z"), ZoneOffset.UTC), 5, 30);

        sut.reconcileUnknownPayments();

        verify(paymentService).failPayment(20L, "카드 거절");
    }

    /*
     * [2026-09-27 KST] PENDING 재확인도 pgTid IS NOT NULL 조건이 붙은 새 쿼리 메서드로 조회하는지
     * 확인한다 — confirm을 실제로 시도했으나(pgTid에 paymentKey가 남음) 프로세스가 죽어 상태 전이를
     * 못한 경우다. 이탈(pgTid == null)은 쿼리 자체에서 걸러지므로 이 서비스 레벨 테스트로는 검증할
     * 수 없다(순수 Mockito 단위 테스트는 DB 쿼리 조건을 실행하지 않는다) — 리포지토리가 그 조건에
     * 맞는 값만 돌려준다고 가정하고, 이 서비스는 그 결과를 그대로 처리하는지만 본다.
     */
    @Test
    void PENDING도_pgTid가_있는_confirm_시도_건만_새_쿼리로_조회해서_재확인한다() {
        Payment attempted = pendingPaymentWithConfirmAttempt(30L, 300L, "pk_abc");
        LocalDateTime paidAt = LocalDateTime.of(2026, 9, 6, 10, 30);
        when(paymentRepository.findByStatusAndReconciliationIsolatedFalseAndPgTidIsNotNullAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc(
                eq(PaymentStatus.PENDING), anyLong(), any(), any()))
                .thenReturn(List.of(attempted), List.of());
        when(paymentGateway.inquire(attempted.getPgOrderNo()))
                .thenReturn(PaymentGatewayInquiryResult.approved("pg_300", paidAt, PaymentMethod.CARD));
        when(paymentService.approvePayment(eq(30L), any()))
                .thenReturn(new PaymentResult(30L, 300L, PaymentMethod.CARD, 25_800,
                        PaymentStatus.PAID, "pg_300", paidAt));
        PaymentReconciliationService sut = new PaymentReconciliationService(paymentRepository, paymentGateway,
                paymentService, attemptService,
                Clock.fixed(Instant.parse("2026-09-06T11:00:00Z"), ZoneOffset.UTC), 5, 30);

        sut.reconcilePendingPayments();

        verify(paymentGateway).inquire(attempted.getPgOrderNo());
        verify(paymentService).approvePayment(eq(30L), any());
    }

    private static Payment unknownPayment(Long paymentId, Long orderId) {
        Payment payment = Payment.prepare(orderId, 7L, PaymentMethod.CARD, 25_800);
        payment.markUnknown();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        return payment;
    }

    private static Payment pendingPaymentWithConfirmAttempt(Long paymentId, Long orderId, String paymentKey) {
        Payment payment = Payment.prepare(orderId, 7L, PaymentMethod.CARD, 25_800);
        payment.recordConfirmAttempt(paymentKey);
        ReflectionTestUtils.setField(payment, "id", paymentId);
        return payment;
    }
}
