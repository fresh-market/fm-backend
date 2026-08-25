package com.freshmarket.payment.domain;

import com.freshmarket.payment.PaymentApi;
import com.freshmarket.payment.PaymentInfo;
import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.domain.client.PaymentGateway;
import com.freshmarket.payment.domain.entity.Payment;
import com.freshmarket.payment.domain.service.PaymentService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 공개 API는 트랜잭션을 열지 않고, 짧은 DB 트랜잭션과 외부 PG 호출의 경계를 조립만 한다.
@Component
@RequiredArgsConstructor
class PaymentApiImpl implements PaymentApi {

    private final PaymentService paymentService;
    private final PaymentGateway paymentGateway;

    @Override
    public PaymentResult requestPayment(PaymentRequest request) {
        PaymentPreparation preparation = paymentService.preparePayment(request);
        Payment payment = preparation.payment();
        if (!preparation.newlyPrepared()) {
            return PaymentResult.from(payment);
        }
        /*
         * TODO: 실제 PG Gateway를 붙일 때 아래를 함께 구현한다.
         * - PG에 orderId 기반 merchant payment key를 보내 중복 승인을 막는다.
         * - 명확한 거절은 FAILED로 전이하고 PaymentFailedEvent를 발행한다.
         * - 타임아웃/연결 단절처럼 승인 결과를 모르는 경우는 UNKNOWN으로 기록한 뒤,
         *   PG 거래 조회 API와 batch 프로필 복구 스케줄러로 PAID 또는 FAILED를 확정한다.
         * - 최종 결제 만료는 order 이벤트로 전달해 재고 예약과 쿠폰 사용을 되돌린다.
         * - Gateway HTTP 연결/읽기 타임아웃, PG 원문 응답 코드 로그, 성공·실패 메트릭을 추가한다.
         */
        return paymentService.approvePayment(payment.getId(), paymentGateway.request(payment.toRequest()));
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
