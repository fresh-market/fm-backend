package com.freshmarket.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/*
 * 선착순 회로 둘이 Prometheus 로 나가는지 고정한다.
 *
 * 회로를 CircuitBreaker.of 로 따로 만들면 Micrometer 바인더가 그 회로를 못 본다. 그러면
 * 회로가 열려 요청을 거절해도 그 사실이 지표에 안 남는다. 실제로 그래서 부하 시험에서
 * 순번 확보 회로가 5,134건을 거절했는데 왜 열렸는지 알아내지 못했다 (2026-08-30).
 *
 * 지표 이름과 라벨 값을 문자열로 그대로 적는다. 대시보드와 알람이 이 이름에 의존하므로
 * 이름이 바뀌면 그것들이 조용히 죽는다. 여기서 잡는다.
 */
class CouponCircuitMetricsTest {

    private static final String STATE = "resilience4j.circuitbreaker.state";
    private static final String CALLS = "resilience4j.circuitbreaker.calls";

    private static final CouponCircuitProperties.Settings SETTINGS =
            new CouponCircuitProperties.Settings(50, 100, 20,
                    Duration.ofSeconds(10), 5, Duration.ofMillis(50));

    @Test
    void 순번_확보_회로가_지표로_나간다() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuits).bindTo(meters);

        CouponCircuits.forRedis(circuits, SETTINGS);

        assertThat(meters.find(STATE).tag("name", "couponSeq").gauges())
                .as("순번 확보 회로의 상태 지표")
                .isNotEmpty();
    }

    @Test
    void 쓰기_회로가_지표로_나간다() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuits).bindTo(meters);

        CouponCircuits.forDatabaseWrite(circuits, SETTINGS);

        assertThat(meters.find(STATE).tag("name", "couponWrite").gauges())
                .as("쓰기 회로의 상태 지표")
                .isNotEmpty();
    }

    /*
     * 느린 호출을 세는 것이 이 회로의 요점이다. 부하 시험에서 회로가 열린 이유가
     * 실패가 아니라 느림이었는데, 그 비율을 볼 수단이 없어 원인을 못 좁혔다.
     */
    @Test
    void 느린_호출이_지표에_잡힌다() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuits).bindTo(meters);

        CircuitBreaker sut = CouponCircuits.forRedis(circuits, SETTINGS);
        sut.executeCallable(() -> {
            Thread.sleep(60);   // slow-call 문턱 50ms 를 넘긴다
            return "ok";
        });

        assertThat(meters.find(CALLS).tag("name", "couponSeq").tag("kind", "successful").timers())
                .as("성공했지만 느린 호출도 집계된다")
                .isNotEmpty();
        assertThat(sut.getMetrics().getNumberOfSlowSuccessfulCalls())
                .as("회로가 이 호출을 느리다고 셌다")
                .isEqualTo(1);
    }
}
