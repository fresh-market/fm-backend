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
import com.freshmarket.payment.internal.service.PaymentResultOutboxDispatchService;
import com.freshmarket.payment.internal.service.PaymentService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 공개 API는 트랜잭션을 열지 않고, 짧은 DB 트랜잭션과 외부 PG 호출의 경계를 조립만 한다.
@Component
@RequiredArgsConstructor
class PaymentApiImpl implements PaymentApi {

    private final PaymentService paymentService;
    private final PaymentResultOutboxDispatchService paymentResultOutboxDispatchService;
    private final PaymentGateway paymentGateway;

    @Override
    public PaymentResult requestPayment(PaymentRequest request) {
        PaymentPreparation preparation = paymentService.preparePayment(request);
        Payment payment = preparation.payment();
        if (!preparation.newlyPrepared()) {
            return PaymentResult.from(payment);
        }

        /*
         * [2026-09-05 19:13 KST] 남은 TODO(실제 PG Gateway를 붙일 때 함께 구현):
         *
         * [중복 승인·결과 수렴]
         * - PG에 orderId 기반의 merchant payment key(또는 PG가 요구하는 고유 주문번호)를 보낸다.
         *   같은 결제 요청·웹훅·복구 작업이 여러 번 와도 PG 승인과 내부 상태가 한 건으로 수렴해야 한다.
         * - PG 웹훅은 서명, 이벤트 ID, 결제 금액, merchant key를 검증하고, 이벤트 ID도 별도로 멱등 처리한다.
         *
         * [운영]
         * - Gateway HTTP 연결/읽기 타임아웃, 제한된 재시도 정책, PG 원문 응답 코드·추적 ID 로그,
         *   성공·실패·UNKNOWN·복구 지연 메트릭과 알림을 추가한다.
         */
        PaymentGatewayApproval approval;
        try {
            approval = paymentGateway.request(payment.toRequest());
        } catch (PaymentGatewayRejectedException e) {
            return dispatchResult(paymentService.failPayment(payment.getId(), e.getMessage()));
        } catch (PaymentGatewayUnknownException e) {
            return paymentService.markPaymentUnknown(payment.getId(), e.getMessage());
        }

        /*
         * 승인 반영 트랜잭션과 결과 outbox 전달은 이미 각각 자기 실패 처리를 가진다.
         * 여기서 둘을 RuntimeException으로 함께 잡아 UNKNOWN으로 바꾸면, PAID 커밋 뒤 전달 단계에서
         * 난 예외까지 "승인 반영 실패"로 오인한다. DB 반영이 롤백된 경우는 PENDING으로 남아
         * reconciliation이 PG 조회로 확정하고, PAID가 커밋된 경우는 결과 outbox가 재전송한다.
         */
        return dispatchResult(paymentService.approvePayment(payment.getId(), approval));
    }

    private PaymentResult dispatchResult(PaymentResult result) {
        paymentResultOutboxDispatchService.dispatchForPayment(result.paymentId());
        return result;
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
