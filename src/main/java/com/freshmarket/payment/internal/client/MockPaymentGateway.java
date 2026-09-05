package com.freshmarket.payment.internal.client;

import com.freshmarket.payment.PaymentRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 개발 단계의 PG 대역. 실제 PG 교체 시 PaymentGateway 계약은 유지한다.
@Component
@RequiredArgsConstructor
public class MockPaymentGateway implements PaymentGateway {

    private final Clock clock;

    @Override
    public PaymentGatewayApproval request(PaymentRequest request) {
        // TODO: WebClient로 PG 결제 승인 API를 호출하고, 응답의 거래번호와 승인시각을 매핑한다.
        // 실제로 외부api의 응답에서는 더 많은 데이터를 받는다.
        return new PaymentGatewayApproval("mock_" + UUID.randomUUID(), LocalDateTime.now(clock));
    }

    /*
     * [2026-09-05 18:28 KST] request()가 항상 성공만 반환해 Mock으로는 UNKNOWN이 될 일이 없다.
     * 그래서 복구 배치가 이 메서드를 부를 일도 실제로는 없지만, 인터페이스 계약이라 구현은 해둔다 —
     * 호출되면 request()와 같은 패턴으로 즉시 승인 응답을 준다.
     */
    @Override
    public PaymentGatewayInquiryResult inquire(Long orderId) {
        return PaymentGatewayInquiryResult.approved("mock_" + UUID.randomUUID(), LocalDateTime.now(clock));
    }
}
