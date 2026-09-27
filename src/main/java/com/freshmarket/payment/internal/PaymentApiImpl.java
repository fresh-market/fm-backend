package com.freshmarket.payment.internal;

import com.freshmarket.payment.PaymentApi;
import com.freshmarket.payment.PaymentInfo;
import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.internal.entity.Payment;
import com.freshmarket.payment.internal.service.PaymentService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/*
 * [2026-09-27 KST] 예전에는 여기서 PENDING 행 생성과 PG 승인 호출까지 한 번에 조립했다(짧은 DB
 * 트랜잭션과 외부 PG 호출의 경계를 나누는 것이 이 클래스의 존재 이유였다). 토스 연동으로 PG 승인은
 * 프론트가 결제창 인증을 마친 뒤 별도 확정(confirm) 요청에서만 일어나게 되면서, 그 조립 책임은
 * PaymentConfirmationService로 옮겼다 — 이 클래스는 이제 PENDING 준비만 하고 끝나는 얇은 위임이라,
 * 더 이상 PG/outbox를 알 필요가 없다.
 */
@Component
@RequiredArgsConstructor
class PaymentApiImpl implements PaymentApi {

    private final PaymentService paymentService;

    @Override
    public PaymentResult preparePayment(PaymentRequest request) {
        PaymentPreparation preparation = paymentService.preparePayment(request);
        return PaymentResult.from(preparation.payment());
    }

    @Override
    public Optional<PaymentInfo> findPaymentInfo(Long orderId) {
        return paymentService.findPayment(orderId).map(PaymentApiImpl::toPaymentInfo);
    }

    // 내부 엔티티를 공개 계약으로 변환하는 책임은 공개 API 구현체에 둔다.
    private static PaymentInfo toPaymentInfo(Payment payment) {
        return new PaymentInfo(payment.getId(), payment.getMethod(), payment.getAmount(),
                payment.getStatus(), payment.getPaidAt());
    }
}
