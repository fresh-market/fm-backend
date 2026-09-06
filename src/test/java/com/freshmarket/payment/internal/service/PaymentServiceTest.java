package com.freshmarket.payment.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.common.event.OrderPaymentApprovedEvent;
import com.freshmarket.common.event.OrderPaymentFailedEvent;
import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.PaymentStatus;
import com.freshmarket.payment.internal.PaymentPreparation;
import com.freshmarket.payment.internal.client.PaymentGatewayApproval;
import com.freshmarket.payment.internal.entity.Payment;
import com.freshmarket.payment.internal.exception.PaymentErrorCode;
import com.freshmarket.payment.internal.exception.PaymentException;
import com.freshmarket.payment.internal.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PaymentService sut;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        sut = new PaymentService(paymentRepository, clock, eventPublisher);
    }

    @Test
    void 결제가_없으면_PENDING_결제를_원자적으로_만든다() {
        PaymentRequest request = new PaymentRequest(1L, 25800, PaymentMethod.CARD);
        Payment payment = payment(10L);
        when(paymentRepository.insertIfAbsent(anyLong(), anyString(), anyInt(), any())).thenReturn(1);
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));

        PaymentPreparation result = sut.preparePayment(request);

        assertThat(result.payment()).isSameAs(payment);
        assertThat(result.newlyPrepared()).isTrue();
        verify(paymentRepository).insertIfAbsent(anyLong(), anyString(), anyInt(), any());
    }

    @Test
    void 같은_주문의_결제가_이미_있으면_재사용한다() {
        Payment existing = payment(10L);
        when(paymentRepository.insertIfAbsent(anyLong(), anyString(), anyInt(), any())).thenReturn(0);
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(existing));

        PaymentPreparation result = sut.preparePayment(new PaymentRequest(1L, 25800, PaymentMethod.CARD));

        assertThat(result.payment()).isSameAs(existing);
        assertThat(result.newlyPrepared()).isFalse();
    }

    @Test
    void 기존_결제와_금액이나_수단이_다르면_거절한다() {
        when(paymentRepository.insertIfAbsent(anyLong(), anyString(), anyInt(), any())).thenReturn(0);
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment(10L)));

        assertThatThrownBy(() -> sut.preparePayment(new PaymentRequest(1L, 30000, PaymentMethod.CARD)))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_REQUEST_MISMATCH);
    }

    @Test
    void PENDING_결제를_만든_뒤_조회되지_않으면_예외가_발생한다() {
        when(paymentRepository.insertIfAbsent(anyLong(), anyString(), anyInt(), any())).thenReturn(1);
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.preparePayment(new PaymentRequest(1L, 25800, PaymentMethod.CARD)))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    void gateway_승인_결과로_결제를_완료한다() {
        Payment payment = payment(10L);
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));
        LocalDateTime paidAt = LocalDateTime.of(2026, 8, 21, 15, 30);

        PaymentResult result = sut.approvePayment(10L, new PaymentGatewayApproval("mock_123", paidAt));

        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(result.pgTid()).isEqualTo("mock_123");
        assertThat(result.paidAt()).isEqualTo(paidAt);
        verify(eventPublisher).publishEvent(new OrderPaymentApprovedEvent(payment.getOrderId(), 10L, paidAt));
    }

    @Test
    void 없는_결제는_승인할_수_없다() {
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.approvePayment(10L,
                new PaymentGatewayApproval("mock_123", LocalDateTime.now())))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    void 이미_승인된_결제는_다시_승인하지_않는다() {
        Payment payment = payment(10L);
        payment.approve("mock_123", LocalDateTime.of(2026, 8, 21, 15, 30));
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));

        PaymentResult result = sut.approvePayment(10L,
                new PaymentGatewayApproval("different_tid", LocalDateTime.of(2026, 8, 21, 16, 0)));

        assertThat(result.pgTid()).isEqualTo("mock_123");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void PENDING이_아닌_결제는_승인할_수_없다() {
        Payment payment = payment(10L);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.CANCELED);
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> sut.approvePayment(10L,
                new PaymentGatewayApproval("mock_123", LocalDateTime.now())))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_NOT_PENDING);
    }

    @Test
    void gateway_거절_결과로_결제를_실패처리한다() {
        Payment payment = payment(10L);
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));

        PaymentResult result = sut.failPayment(10L, "카드 한도 초과");

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        verify(eventPublisher).publishEvent(new OrderPaymentFailedEvent(payment.getOrderId(), 10L, "카드 한도 초과"));
    }

    @Test
    void 없는_결제는_실패처리할_수_없다() {
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.failPayment(10L, "카드 한도 초과"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
    }

    /*
     * 재시도로 같은 실패 결과가 두 번 들어와도 OrderPaymentFailedEvent를 두 번 발행하지 않는다 —
     * order 쪽이 이미 취소 처리한 주문을 다시 취소 시도할 필요가 없다.
     */
    @Test
    void 이미_실패한_결제는_다시_실패처리하지_않는다() {
        Payment payment = payment(10L);
        payment.fail();
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));

        PaymentResult result = sut.failPayment(10L, "재시도로 들어온 동일 실패");

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    /*
     * PG 응답이 불확실(timeout/응답유실)하면 UNKNOWN으로만 남기고, order에게는 아무 이벤트도
     * 보내지 않는다 — 아직 결론이 안 났으니 주문을 건드리면 안 된다(PaymentService 클래스 주석 참고).
     */
    @Test
    void PG_응답이_불확실하면_UNKNOWN으로_남기고_order에는_알리지_않는다() {
        Payment payment = payment(10L);
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(payment));

        PaymentResult result = sut.markPaymentUnknown(10L, "PG 응답 timeout");

        assertThat(result.status()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 없는_결제는_UNKNOWN으로_남길_수_없다() {
        when(paymentRepository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.markPaymentUnknown(10L, "PG 응답 timeout"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    void 잘못된_결제_요청은_저장하기_전에_거절한다() {
        assertThatThrownBy(() -> sut.preparePayment(null))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
    }

    @Test
    void 결제_엔티티는_PENDING이_아니면_직접_승인할_수_없다() {
        Payment payment = payment(10L);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.CANCELED);

        assertThatThrownBy(() -> payment.approve("mock_123", LocalDateTime.of(2026, 8, 21, 15, 30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 주문_ID로_결제_상세_표시용_정보를_조회한다() {
        Payment payment = payment(10L);
        payment.approve("mock_123", LocalDateTime.of(2026, 8, 21, 15, 30));
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));

        Optional<Payment> result = sut.findPayment(1L);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(result.orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void 결제가_없는_주문은_빈_결제_정보를_반환한다() {
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());

        Optional<Payment> result = sut.findPayment(1L);

        assertThat(result).isEmpty();
    }

    private Payment payment(Long id) {
        Payment payment = Payment.prepare(1L, PaymentMethod.CARD, 25800);
        ReflectionTestUtils.setField(payment, "id", id);
        return payment;
    }
}
