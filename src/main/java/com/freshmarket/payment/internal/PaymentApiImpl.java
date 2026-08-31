package com.freshmarket.payment.internal;

import com.freshmarket.payment.PaymentApi;
import com.freshmarket.payment.PaymentInfo;
import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.internal.client.PaymentGateway;
import com.freshmarket.payment.internal.entity.Payment;
import com.freshmarket.payment.internal.service.PaymentService;
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
         *
         * [중복 승인·결과 수렴]
         * - PG에 orderId 기반의 merchant payment key(또는 PG가 요구하는 고유 주문번호)를 보낸다.
         *   같은 결제 요청·웹훅·복구 작업이 여러 번 와도 PG 승인과 내부 상태가 한 건으로 수렴해야 한다.
         * - PG 웹훅은 서명, 이벤트 ID, 결제 금액, merchant key를 검증하고, 이벤트 ID도 별도로 멱등 처리한다.
         * - PG 승인 성공 후 DB의 Payment/Order/재고 확정 트랜잭션이 실패할 수 있다. 이 경우 PG 거래를
         *   재조회해 PAID로 복구할 수 있어야 하며, 단순히 PENDING Payment를 반환하고 끝내면 안 된다.
         *
         * [실패·불확실 상태]
         * - 명확한 거절은 FAILED로 전이하고 PaymentFailedEvent를 발행한다.
         * - 타임아웃·연결 단절처럼 PG 승인 결과를 알 수 없으면 UNKNOWN으로 기록한다. 재호출로 이중
         *   승인하지 말고 PG 거래 조회 API와 복구 배치로 PAID 또는 FAILED를 확정한다.
         * - 결제 유효시간 만료·최종 실패는 order 이벤트로 전달해 PAYMENT_PENDING 주문을 취소하고,
         *   재고 예약 및 쿠폰 사용을 같은 보상 흐름에서 되돌린다.
         *
         * [운영]
         * - Gateway HTTP 연결/읽기 타임아웃, 제한된 재시도 정책, PG 원문 응답 코드·추적 ID 로그,
         *   성공·실패·UNKNOWN·복구 지연 메트릭과 알림을 추가한다.
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
