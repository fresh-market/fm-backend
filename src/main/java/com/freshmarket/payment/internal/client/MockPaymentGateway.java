package com.freshmarket.payment.internal.client;

import com.freshmarket.payment.PaymentRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/*
 * PG 대역이다. 항상 승인만 반환한다.
 *
 * 실제 PG 구현체가 아직 없어 운영(prod)도 이 대역을 쓴다. 운영에서 결제는 전부 승인된다.
 * prod 를 빼면 PaymentApiImpl 과 PaymentReconciliationService 가 주입받을 빈이 없어
 * 앱과 배치 인스턴스(prod,batch)가 기동하지 못한다 (#203 배포 실패).
 *
 * integrationTest 는 FakePaymentGatewayIntegrationTest 가 따로 맡으므로 여기 넣지 않는다.
 * 넣으면 PaymentGateway 빈이 둘이 되어 통합 테스트 컨텍스트가 뜨지 않는다.
 *
 * 실제 PG 구현체를 추가할 때는 그 구현체에 @Profile("prod") 를 거는 것과 여기서 prod 를 빼는 것을
 * 같은 커밋에서 한다. 둘을 나누면 운영에 PaymentGateway 가 0개인 순간이 생긴다.
 */
@Component
@Profile({"local", "prod"})
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
