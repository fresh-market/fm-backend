package com.freshmarket.payment.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.PaymentStatus;
import com.freshmarket.payment.domain.PaymentPreparation;
import com.freshmarket.payment.domain.client.PaymentGatewayApproval;
import com.freshmarket.payment.domain.entity.Payment;
import com.freshmarket.payment.domain.exception.PaymentErrorCode;
import com.freshmarket.payment.domain.exception.PaymentException;
import com.freshmarket.payment.domain.repository.PaymentRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    private PaymentService sut;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        sut = new PaymentService(paymentRepository, clock);
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
