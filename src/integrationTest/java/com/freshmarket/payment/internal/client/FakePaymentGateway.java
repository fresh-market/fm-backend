package com.freshmarket.payment.internal.client;

import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.internal.client.exception.PaymentGatewayRejectedException;
import com.freshmarket.payment.internal.client.exception.PaymentGatewayUnknownException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * [2026-09-05 17:36 KST] 통합 테스트 전용 PG 대역이다. src/integrationTest에만 존재하며 프로덕션
 * 빈 그래프에는 올라가지 않는다 — MockPaymentGateway(개발용, 항상 승인만 반환)와는 목적이 다르다.
 *
 * willApprove/willReject/willTimeout/willLoseResponse로 시나리오를 미리 등록해두면, request()가
 * 호출될 때마다 등록한 순서대로 하나씩 소비하며 그대로 응답하거나 예외를 던진다. 등록된 시나리오가
 * 없으면 기본값인 승인으로 응답한다 — 재시도 흐름에서 "첫 호출은 timeout, 재확인 결과는 승인"처럼
 * 호출마다 다른 결과를 주는 시나리오도 표현할 수 있다.
 *
 * callCount()로 실제 호출 횟수를 확인할 수 있다. idempotency key 적용 후 "같은 요청을 재시도해도
 * PG가 중복 호출되지 않는다"를 검증하는 데 쓴다.
 *
 * [2026-09-05 18:28 KST] inquire() 시나리오 큐를 별도로 추가했다. request()와 기본값을 다르게
 * 뒀다 — request()는 시나리오가 없으면 승인으로 기본 동작하지만, inquire()는 STILL_PROCESSING으로
 * 기본 동작한다. inquire()를 호출하는 건 결국 복구 배치 테스트뿐인데, 그 테스트가 시나리오를
 * 깜빡 등록 안 했을 때 조용히 승인 처리되어 버그를 가리는 것보다, 아무 것도 확정되지 않아 테스트가
 * 눈에 띄게 실패하는 쪽이 안전하다.
 */
public class FakePaymentGateway implements PaymentGateway {

    private final Clock clock;
    private final Deque<Scenario> scenarios = new ArrayDeque<>();
    private final Deque<InquiryScenario> inquiryScenarios = new ArrayDeque<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicInteger inquireCallCount = new AtomicInteger();

    public FakePaymentGateway(Clock clock) {
        this.clock = clock;
    }

    @Override
    public PaymentGatewayApproval request(PaymentRequest request) {
        callCount.incrementAndGet();
        Scenario scenario = scenarios.poll();
        return (scenario == null ? Scenario.approve() : scenario).resolve(clock);
    }

    @Override
    public PaymentGatewayInquiryResult inquire(Long orderId) {
        inquireCallCount.incrementAndGet();
        InquiryScenario scenario = inquiryScenarios.poll();
        return (scenario == null ? InquiryScenario.stillProcessing() : scenario).resolve(clock);
    }

    public int callCount() {
        return callCount.get();
    }

    public int inquireCallCount() {
        return inquireCallCount.get();
    }

    public void willApprove() {
        scenarios.add(Scenario.approve());
    }

    public void willReject(String reason) {
        scenarios.add(Scenario.reject(reason));
    }

    public void willTimeout() {
        scenarios.add(Scenario.timeout());
    }

    public void willLoseResponse() {
        scenarios.add(Scenario.loseResponse());
    }

    public void willInquireApprove() {
        inquiryScenarios.add(InquiryScenario.approve());
    }

    public void willInquireReject(String reason) {
        inquiryScenarios.add(InquiryScenario.reject(reason));
    }

    public void willInquireStillProcessing() {
        inquiryScenarios.add(InquiryScenario.stillProcessing());
    }

    // 테스트 간 상태가 새지 않도록 시나리오 큐와 호출 횟수를 초기화한다. 빈으로 재사용할 때 @BeforeEach에서 부른다.
    public void reset() {
        scenarios.clear();
        inquiryScenarios.clear();
        callCount.set(0);
        inquireCallCount.set(0);
    }

    private record Scenario(Type type, String reason) {

        enum Type {APPROVE, REJECT, TIMEOUT, LOSE_RESPONSE}

        static Scenario approve() {
            return new Scenario(Type.APPROVE, null);
        }

        static Scenario reject(String reason) {
            return new Scenario(Type.REJECT, reason);
        }

        static Scenario timeout() {
            return new Scenario(Type.TIMEOUT, null);
        }

        static Scenario loseResponse() {
            return new Scenario(Type.LOSE_RESPONSE, null);
        }

        PaymentGatewayApproval resolve(Clock clock) {
            return switch (type) {
                case APPROVE -> new PaymentGatewayApproval("fake_" + UUID.randomUUID(), LocalDateTime.now(clock));
                case REJECT -> throw new PaymentGatewayRejectedException(reason);
                case TIMEOUT -> throw new PaymentGatewayUnknownException("PG 응답 timeout", null);
                case LOSE_RESPONSE -> throw new PaymentGatewayUnknownException("PG 응답 유실", null);
            };
        }
    }

    private record InquiryScenario(Type type, String reason) {

        enum Type {APPROVE, REJECT, STILL_PROCESSING}

        static InquiryScenario approve() {
            return new InquiryScenario(Type.APPROVE, null);
        }

        static InquiryScenario reject(String reason) {
            return new InquiryScenario(Type.REJECT, reason);
        }

        static InquiryScenario stillProcessing() {
            return new InquiryScenario(Type.STILL_PROCESSING, null);
        }

        PaymentGatewayInquiryResult resolve(Clock clock) {
            return switch (type) {
                case APPROVE -> PaymentGatewayInquiryResult.approved(
                        "fake_" + UUID.randomUUID(), LocalDateTime.now(clock));
                case REJECT -> PaymentGatewayInquiryResult.rejected(reason);
                case STILL_PROCESSING -> PaymentGatewayInquiryResult.stillProcessing();
            };
        }
    }
}
