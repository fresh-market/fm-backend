package com.freshmarket.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.EventProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/*
 * 선착순 회로 둘이 Prometheus 로 나가는지, 그리고 그 대가로 호출 경로가 무거워지지 않았는지
 * 함께 고정한다. 둘 중 하나만 지키면 지난번 사고가 되풀이된다.
 *
 * 지표가 없으면 회로가 왜 열렸는지 알 수 없고, 이벤트로 지표를 만들면 호출마다 객체가 생겨
 * GC 정지가 느림 판정 기준을 넘긴다. 2026-08-30 부하 시험에서 발급이 7,281건에서 295건으로
 * 떨어진 것이 후자다.
 */
class CouponCircuitMetricsTest {

    private static final String STATE = "resilience4j.circuitbreaker.state";
    private static final String SLOW_RATE = "resilience4j.circuitbreaker.slow.call.rate";
    private static final String FAILURE_RATE = "resilience4j.circuitbreaker.failure.rate";
    private static final String NOT_PERMITTED = "resilience4j.circuitbreaker.not.permitted.calls";

    private static final CouponCircuitProperties.Settings SETTINGS =
            new CouponCircuitProperties.Settings(50, 100, 20,
                    Duration.ofSeconds(10), 5, Duration.ofMillis(50));

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void 순번_확보_회로가_지표로_나간다() {
        CouponCircuits.forRedis(meters, SETTINGS);

        assertThat(meters.find(STATE).tag("name", "couponSeq").tag("state", "open").gauge())
                .as("순번 확보 회로의 열림 상태")
                .isNotNull();
        assertThat(meters.find(SLOW_RATE).tag("name", "couponSeq").gauge()).isNotNull();
        assertThat(meters.find(FAILURE_RATE).tag("name", "couponSeq").gauge()).isNotNull();
        assertThat(meters.find(NOT_PERMITTED).tag("name", "couponSeq").functionCounter())
                .as("회로가 즉시 거절한 건수")
                .isNotNull();
    }

    @Test
    void 쓰기_회로가_지표로_나간다() {
        CouponCircuits.forDatabaseWrite(meters, SETTINGS);

        assertThat(meters.find(STATE).tag("name", "couponWrite").tag("state", "open").gauge())
                .isNotNull();
        assertThat(meters.find(SLOW_RATE).tag("name", "couponWrite").gauge()).isNotNull();
    }

    /*
     * 이것이 이 파일에서 제일 중요한 검사다.
     *
     * 회로에 이벤트 소비자가 하나라도 붙으면 resilience4j 의 hasConsumers() 우회로가 막히고,
     * 호출마다 이벤트 객체가 ZonedDateTime.now 와 함께 생긴다. 지표를 레지스트리 경유로
     * 만들면 스프링 부트가 그 소비자를 자동으로 붙인다. 그래서 폴링 게이지로 낸다.
     */
    @Test
    void 지표를_붙여도_호출_경로에_이벤트_소비자가_생기지_않는다() {
        CircuitBreaker seq = CouponCircuits.forRedis(meters, SETTINGS);
        CircuitBreaker write = CouponCircuits.forDatabaseWrite(meters, SETTINGS);

        assertThat(hasConsumers(seq))
                .as("순번 확보 회로에 이벤트 소비자가 없다")
                .isFalse();
        assertThat(hasConsumers(write))
                .as("쓰기 회로에 이벤트 소비자가 없다")
                .isFalse();
    }

    /*
     * hasConsumers 는 EventProcessor 에 있고 CircuitBreaker.EventPublisher 에는 없다.
     * 회로가 돌려주는 발행자가 그 EventProcessor 자신이라 형변환으로 물어본다.
     */
    private static boolean hasConsumers(CircuitBreaker circuit) {
        return ((EventProcessor<?>) circuit.getEventPublisher()).hasConsumers();
    }

    // 게이지가 회로에 물어본 값을 그대로 낸다. 등록만 되고 값이 안 따라오면 소용이 없다
    @Test
    void 게이지가_회로의_현재값을_따라간다() throws Exception {
        CircuitBreaker sut = CouponCircuits.forRedis(meters, SETTINGS);

        assertThat(meters.find(STATE).tag("name", "couponSeq").tag("state", "closed").gauge().value())
                .isEqualTo(1.0);

        sut.transitionToOpenState();
        try {
            sut.executeCallable(() -> "막힌다");
        } catch (Exception expected) {
            // 회로가 열려 있어 거절된다. 그 거절이 지표에 잡히는지가 이 시험의 요점이다
        }

        assertThat(meters.find(STATE).tag("name", "couponSeq").tag("state", "open").gauge().value())
                .as("열린 뒤 open 게이지가 1 이 된다")
                .isEqualTo(1.0);
        assertThat(meters.find(STATE).tag("name", "couponSeq").tag("state", "closed").gauge().value())
                .isEqualTo(0.0);
        assertThat(meters.find(NOT_PERMITTED).tag("name", "couponSeq").functionCounter().count())
                .as("거절 한 건이 세어진다")
                .isEqualTo(1.0);
    }
}
