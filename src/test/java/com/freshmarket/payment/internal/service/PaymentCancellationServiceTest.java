package com.freshmarket.payment.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.internal.client.PaymentGateway;
import com.freshmarket.payment.internal.entity.Payment;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentCancellationServiceTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentRefundAttemptService refundAttemptService;

    private PaymentCancellationService sut;

    @BeforeEach
    void setUp() {
        sut = new PaymentCancellationService(paymentService, paymentGateway, refundAttemptService);
    }

    @Test
    void PAID_결제는_PG에_취소를_요청하고_상태를_확정한다() {
        Payment payment = payment(10L);
        payment.approve("toss_key_1", LocalDateTime.of(2026, 8, 21, 15, 30), PaymentMethod.CARD);
        when(paymentService.beginCancel(10L)).thenReturn(Optional.of(payment));

        sut.cancelForRefund(10L, "이미 취소된 주문에 뒤늦게 결제가 승인됨");

        verify(paymentGateway).cancel(eq("toss_key_1"), eq("이미 취소된 주문에 뒤늦게 결제가 승인됨"));
        verify(paymentService).finishCancel(10L, "이미 취소된 주문에 뒤늦게 결제가 승인됨");
        verify(refundAttemptService, never()).recordUnresolvedAttempt(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    /*
     * [2026-09-27 KST] 이벤트 재전달 등으로 같은 취소 요청이 두 번 들어와도, 첫 시도에서 이미
     * CANCELED로 확정됐다면(beginCancel이 PAID가 아니라고 판단) PG를 다시 부르지 않는다.
     */
    @Test
    void PAID가_아니면_PG를_부르지_않고_건너뛴다() {
        when(paymentService.beginCancel(10L)).thenReturn(Optional.empty());

        sut.cancelForRefund(10L, "이미 취소된 주문에 뒤늦게 결제가 승인됨");

        verify(paymentGateway, never()).cancel(any(), any());
        verify(paymentService, never()).finishCancel(any(), any());
    }

    /*
     * [2026-09-28 KST] 아직 상한(MAX_REFUND_ATTEMPTS)에 도달하지 않았다면, 예외를 다시 던져
     * outbox 재전달로 다음 배치 주기에 재시도되도록 한다(클래스 주석 참고).
     */
    @Test
    void 취소_실패가_상한에_못_미치면_예외를_다시_던져_다음_주기_재시도를_유도한다() {
        Payment payment = payment(10L);
        payment.approve("toss_key_1", LocalDateTime.of(2026, 8, 21, 15, 30), PaymentMethod.CARD);
        when(paymentService.beginCancel(10L)).thenReturn(Optional.of(payment));
        RuntimeException failure = new RuntimeException("취소 실패");
        org.mockito.Mockito.doThrow(failure).when(paymentGateway).cancel(any(), any());
        when(refundAttemptService.recordUnresolvedAttempt(10L, 3)).thenReturn(false);

        assertThatThrownBy(() -> sut.cancelForRefund(10L, "사유"))
                .isSameAs(failure);

        verify(refundAttemptService).recordUnresolvedAttempt(10L, 3);
        verify(paymentService, never()).finishCancel(any(), any());
    }

    /*
     * [2026-09-28 KST] 상한을 넘겨 격리되면 더 이상 예외를 전파하지 않는다 — outbox가
     * dispatched로 확정되어 무한 재시도가 멈추고, 이후는 운영자가 직접 확인한다.
     */
    @Test
    void 취소_실패가_상한을_넘기면_격리하고_예외를_삼킨다() {
        Payment payment = payment(10L);
        payment.approve("toss_key_1", LocalDateTime.of(2026, 8, 21, 15, 30), PaymentMethod.CARD);
        when(paymentService.beginCancel(10L)).thenReturn(Optional.of(payment));
        org.mockito.Mockito.doThrow(new RuntimeException("취소 실패")).when(paymentGateway).cancel(any(), any());
        when(refundAttemptService.recordUnresolvedAttempt(10L, 3)).thenReturn(true);

        sut.cancelForRefund(10L, "사유");

        verify(refundAttemptService).recordUnresolvedAttempt(10L, 3);
        verify(paymentService, never()).finishCancel(any(), any());
    }

    /*
     * [2026-09-28 KST] 이미 격리된 결제는(과거 주기에 이미 상한을 넘김) PG를 다시 부르지 않는다.
     */
    @Test
    void 이미_격리된_결제는_PG를_다시_부르지_않는다() {
        Payment payment = payment(10L);
        payment.approve("toss_key_1", LocalDateTime.of(2026, 8, 21, 15, 30), PaymentMethod.CARD);
        for (int i = 0; i < 3; i++) {
            payment.recordUnresolvedRefundAttempt(3);
        }
        when(paymentService.beginCancel(10L)).thenReturn(Optional.of(payment));

        sut.cancelForRefund(10L, "사유");

        verify(paymentGateway, never()).cancel(any(), any());
        verify(paymentService, never()).finishCancel(any(), any());
        verify(refundAttemptService, never()).recordUnresolvedAttempt(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    private Payment payment(Long id) {
        Payment payment = Payment.prepare(1L, 7L, PaymentMethod.CARD, 25800);
        ReflectionTestUtils.setField(payment, "id", id);
        return payment;
    }
}
