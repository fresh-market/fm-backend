package com.freshmarket.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.freshmarket.payment.PaymentInfo;
import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.PaymentStatus;
import com.freshmarket.payment.internal.entity.Payment;
import com.freshmarket.payment.internal.service.PaymentService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * [2026-09-27 KST] 토스 연동 전에는 이 클래스가 PENDING 준비와 PG 승인 호출까지 조립해, 그 조립
 * 로직(중복 승인 방지, 결과 outbox 실패 시 UNKNOWN으로 오인하지 않는 것 등)을 여기서 검증했다.
 * 이제 그 조립은 PaymentConfirmationService로 옮겨갔고(PaymentConfirmationServiceTest 참고),
 * PaymentApiImpl은 PaymentService에 그대로 위임하는 얇은 클래스가 되어 검증할 분기 자체가 거의
 * 남지 않았다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentApiImplTest {

    @Mock
    private PaymentService paymentService;

    @Test
    void 내부_결제_엔티티를_공개_조회_계약으로_변환한다() {
        Payment payment = Payment.prepare(1L, 7L, PaymentMethod.CARD, 25_800);
        ReflectionTestUtils.setField(payment, "id", 10L);
        payment.approve("mock_123", LocalDateTime.of(2026, 8, 21, 15, 30), PaymentMethod.CARD);
        when(paymentService.findPayment(1L)).thenReturn(Optional.of(payment));
        PaymentApiImpl sut = new PaymentApiImpl(paymentService);

        Optional<PaymentInfo> result = sut.findPaymentInfo(1L);

        assertThat(result).contains(new PaymentInfo(10L, PaymentMethod.CARD, 25_800,
                PaymentStatus.PAID, LocalDateTime.of(2026, 8, 21, 15, 30)));
    }

    @Test
    void 결제_준비를_그대로_위임하고_결과를_공개_계약으로_변환한다() {
        Payment payment = Payment.prepare(1L, 7L, PaymentMethod.CARD, 25_800);
        ReflectionTestUtils.setField(payment, "id", 10L);
        PaymentRequest request = new PaymentRequest(1L, 7L, 25_800, PaymentMethod.CARD);
        when(paymentService.preparePayment(request)).thenReturn(new PaymentPreparation(payment, true));
        PaymentApiImpl sut = new PaymentApiImpl(paymentService);

        PaymentResult result = sut.preparePayment(request);

        assertThat(result).isEqualTo(PaymentResult.from(payment));
        assertThat(result.status()).isEqualTo(PaymentStatus.PENDING);
    }
}
