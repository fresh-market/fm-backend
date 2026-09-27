package com.freshmarket.payment.internal.client;

import com.freshmarket.payment.PaymentMethod;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/*
 * PG 대역이다. 항상 승인만 반환한다.
 *
 * [2026-09-27 KST] 실제 PG 구현체(TossPaymentGateway)가 생기면서 prod를 뺐다 — 운영 결제는 이제
 * TossPaymentGateway가 맡는다. 이 클래스는 local 프로필(로컬 개발/수동 테스트)에서만 뜬다.
 * TossPaymentGateway에 @Profile("prod")를 거는 것과 여기서 prod를 빼는 것을 같은 커밋에서 했다 —
 * 둘을 나누면 그 사이 배포에서 PaymentGateway 빈이 0개인 순간이 생겨 #203과 같은 방식으로 앱/배치
 * 인스턴스가 기동하지 못한다.
 *
 * integrationTest 는 FakePaymentGatewayIntegrationTest 가 따로 맡으므로 여기 넣지 않는다.
 * 넣으면 PaymentGateway 빈이 둘이 되어 통합 테스트 컨텍스트가 뜨지 않는다.
 */
@Component
@Profile("local")
@RequiredArgsConstructor
public class MockPaymentGateway implements PaymentGateway {

    private final Clock clock;

    @Override
    public PaymentGatewayApproval confirm(String paymentKey, String pgOrderNo, int amount) {
        // TODO: WebClient로 토스 결제 승인(confirm) API를 호출하고, 응답의 거래번호·승인시각·결제수단을
        // 매핑한다. 실제로 외부api의 응답에서는 더 많은 데이터를 받는다.
        return new PaymentGatewayApproval("mock_" + UUID.randomUUID(), LocalDateTime.now(clock), PaymentMethod.CARD);
    }

    /*
     * [2026-09-05 18:28 KST] confirm()이 항상 성공만 반환해 Mock으로는 UNKNOWN이 될 일이 없다.
     * 그래서 복구 배치가 이 메서드를 부를 일도 실제로는 없지만, 인터페이스 계약이라 구현은 해둔다 —
     * 호출되면 confirm()과 같은 패턴으로 즉시 승인 응답을 준다.
     */
    @Override
    public PaymentGatewayInquiryResult inquire(String pgOrderNo) {
        return PaymentGatewayInquiryResult.approved("mock_" + UUID.randomUUID(), LocalDateTime.now(clock), PaymentMethod.CARD);
    }
}
