package com.freshmarket.payment.internal.service;

import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.internal.client.PaymentGateway;
import com.freshmarket.payment.internal.client.PaymentGatewayApproval;
import com.freshmarket.payment.internal.client.exception.PaymentGatewayRejectedException;
import com.freshmarket.payment.internal.client.exception.PaymentGatewayUnknownException;
import com.freshmarket.payment.internal.entity.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * [2026-09-27 KST] 토스 결제창에서 돌아온 프론트가 부르는 결제 확정(confirm) 흐름을 조립한다.
 * 예전에는 PaymentApiImpl이 이 조립(짧은 DB 트랜잭션 ↔ 외부 PG 호출의 경계)을 맡았지만, 그 호출이
 * 이제 주문 생성이 아니라 confirm 요청 시점에 일어나면서 여기로 옮겼다 — PaymentApi에는 넣지
 * 않는다. order가 아니라 프론트가 직접 부르는 엔드포인트라 order 도메인이 이 흐름을 알 이유가
 * 없고, 같은 도메인 컨트롤러(PaymentConfirmController)가 이 서비스를 직접 부른다
 * (domain-package-boundary-guideline.md의 "같은 도메인 컨트롤러는 내부 서비스를 직접 쓴다" 규칙
 * 참고). @Transactional을 클래스/메서드 어디에도 걸지 않는다 — PaymentApiImpl이 예전에 그랬듯,
 * 외부 PG 호출(paymentGateway.confirm) 동안 DB 트랜잭션을 잡지 않기 위해서다. 실제 상태 전이는
 * PaymentService의 각 메서드가 자기 트랜잭션을 갖는다.
 */
@Service
@RequiredArgsConstructor
public class PaymentConfirmationService {

    private final PaymentService paymentService;
    private final PaymentResultOutboxDispatchService paymentResultOutboxDispatchService;
    private final PaymentGateway paymentGateway;

    public PaymentResult confirm(Long orderId, Long memberId, int amount, String paymentKey) {
        Payment payment = paymentService.beginConfirm(orderId, memberId, amount, paymentKey);
        if (!payment.isPending() && !payment.isUnknown()) {
            // 이미 확정된 결제다 — 프론트가 새로고침 등으로 confirm을 다시 부른 경우, PG를 다시
            // 부르지 않고 현재 상태를 그대로 돌려준다.
            return PaymentResult.from(payment);
        }

        PaymentGatewayApproval approval;
        try {
            approval = paymentGateway.confirm(paymentKey, payment.getPgOrderNo(), amount);
        } catch (PaymentGatewayRejectedException e) {
            return dispatchResult(paymentService.failPayment(payment.getId(), e.getMessage()));
        } catch (PaymentGatewayUnknownException e) {
            return paymentService.markPaymentUnknown(payment.getId(), e.getMessage());
        }

        /*
         * 승인 반영 트랜잭션과 결과 outbox 전달은 이미 각각 자기 실패 처리를 가진다. 여기서 둘을
         * RuntimeException으로 함께 잡아 UNKNOWN으로 바꾸면, PAID 커밋 뒤 전달 단계에서 난 예외까지
         * "승인 반영 실패"로 오인한다. DB 반영이 롤백된 경우는 PENDING/UNKNOWN으로 남아
         * reconciliation이 PG 조회로 확정하고, PAID가 커밋된 경우는 결과 outbox가 재전송한다.
         */
        return dispatchResult(paymentService.approvePayment(payment.getId(), approval));
    }

    private PaymentResult dispatchResult(PaymentResult result) {
        paymentResultOutboxDispatchService.dispatchForPayment(result.paymentId());
        return result;
    }
}
