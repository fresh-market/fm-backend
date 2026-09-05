package com.freshmarket.payment.internal;

import com.freshmarket.payment.PaymentApi;
import com.freshmarket.payment.PaymentInfo;
import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.internal.client.PaymentGateway;
import com.freshmarket.payment.internal.client.PaymentGatewayApproval;
import com.freshmarket.payment.internal.client.exception.PaymentGatewayRejectedException;
import com.freshmarket.payment.internal.client.exception.PaymentGatewayUnknownException;
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
         * [2026-09-05 17:54 KST] 명확한 거절(FAILED)·미확정(UNKNOWN) 분류를 구현했다. 아래는
         * 실제 PG Gateway를 붙일 때 마저 구현해야 할 부분이다.
         *
         * [중복 승인·결과 수렴]
         * - PG에 orderId 기반의 merchant payment key(또는 PG가 요구하는 고유 주문번호)를 보낸다.
         *   같은 결제 요청·웹훅·복구 작업이 여러 번 와도 PG 승인과 내부 상태가 한 건으로 수렴해야 한다.
         * - PG 웹훅은 서명, 이벤트 ID, 결제 금액, merchant key를 검증하고, 이벤트 ID도 별도로 멱등 처리한다.
         * - PG 승인 성공 후 DB의 Payment/Order/재고 확정 트랜잭션이 실패할 수 있다. 이 경우 PG 거래를
         *   재조회해 PAID로 복구할 수 있어야 하며, 단순히 PENDING Payment를 반환하고 끝내면 안 된다.
         *
         * [실패·불확실 상태 이후 처리 — 아직 없음]
         * - FAILED/UNKNOWN이 되어도 order에 알리는 이벤트가 없어 주문은 PAYMENT_PENDING에 그대로
         *   남는다. 주문 취소·재고 해제·쿠폰 복원으로 이어지는 보상 흐름을 추가해야 한다.
         * - UNKNOWN은 지금 여기서 더 이상 진행되지 않는다. PG 거래 조회 API와 복구 배치로 PAID 또는
         *   FAILED로 재확정하는 로직이 별도로 필요하다.
         *
         * [운영]
         * - Gateway HTTP 연결/읽기 타임아웃, 제한된 재시도 정책, PG 원문 응답 코드·추적 ID 로그,
         *   성공·실패·UNKNOWN·복구 지연 메트릭과 알림을 추가한다.
         */
        try {
            PaymentGatewayApproval approval = paymentGateway.request(payment.toRequest());
            return paymentService.approvePayment(payment.getId(), approval);
        } catch (PaymentGatewayRejectedException e) {
            return paymentService.failPayment(payment.getId(), e.getMessage());
        } catch (PaymentGatewayUnknownException e) {
            return paymentService.markPaymentUnknown(payment.getId(), e.getMessage());
        }
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
