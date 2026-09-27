package com.freshmarket.payment.internal.service;

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

    private PaymentCancellationService sut;

    @BeforeEach
    void setUp() {
        sut = new PaymentCancellationService(paymentService, paymentGateway);
    }

    @Test
    void PAID_결제는_PG에_취소를_요청하고_상태를_확정한다() {
        Payment payment = payment(10L);
        payment.approve("toss_key_1", LocalDateTime.of(2026, 8, 21, 15, 30), PaymentMethod.CARD);
        when(paymentService.beginCancel(10L)).thenReturn(Optional.of(payment));

        sut.cancelForRefund(10L, "이미 취소된 주문에 뒤늦게 결제가 승인됨");

        verify(paymentGateway).cancel(eq("toss_key_1"), eq("이미 취소된 주문에 뒤늦게 결제가 승인됨"));
        verify(paymentService).finishCancel(10L, "이미 취소된 주문에 뒤늦게 결제가 승인됨");
    }

    /*
     * [2026-09-27 KST] 이벤트 재전달 등으로 같은 취소 요청이 두 번 들어와도, 첫 시도에서 이미
     * CANCELED로 확정됐다면(beginCancel이 PAID가 아니라고 판단) PG를 다시 부르지 않는다.
     */
    @Test
    void PAID가_아니면_PG를_부르지_않고_건너뛴다() {
        when(paymentService.beginCancel(10L)).thenReturn(Optional.empty());

        sut.cancelForRefund(10L, "이미 취소된 주문에 뒤늦게 결제가 승인됨");

        verify(paymentGateway, never()).cancel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(paymentService, never()).finishCancel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private Payment payment(Long id) {
        Payment payment = Payment.prepare(1L, 7L, PaymentMethod.CARD, 25800);
        ReflectionTestUtils.setField(payment, "id", id);
        return payment;
    }
}
