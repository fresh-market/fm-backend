package com.freshmarket.coupon.domain;

import com.freshmarket.coupon.domain.exception.DataAccessFailures;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.ToDoubleFunction;
import org.springframework.dao.DataAccessException;

/**
 * 이 클래스가 회로 둘을 같은 규칙으로 만든다. 무엇을 실패로 세느냐만 다르고 나머지 모양은 같다.
 *
 * <p>회로를 {@link CircuitBreaker#of} 로 만들고 지표는 {@link #bind} 가 게이지로 따로 낸다.
 * <b>스프링이 관리하는 {@code CircuitBreakerRegistry} 에 등록하면 안 된다.</b> 등록하면
 * 스프링 부트가 이벤트 소비자를 붙이고, 그때부터 호출마다 이벤트 객체가 생긴다.
 *
 * <p>resilience4j 는 소비자가 없으면 이벤트를 아예 안 만든다
 * ({@code CircuitBreakerStateMachine.publishSuccessEvent} 가 {@code hasConsumers()} 를 먼저 본다).
 * 소비자가 붙는 순간 그 우회로가 막히고, 이벤트 생성자가 호출마다
 * {@code ZonedDateTime.now(clock)} 를 부른다.
 *
 * <p>그 대가를 실측했다 (2026-08-30 부하 시험, 20,000 VU). 레지스트리에 등록했더니 GC 총 시간이
 * 1.49초에서 2.64초로, <b>최대 정지가 104밀리초에서 330밀리초로</b> 늘었다. 순번 회로의 느림
 * 판정 기준이 50밀리초라 <b>정지 한 번이 그 사이의 Redis 호출을 전부 느린 것으로 만든다.</b>
 * 느린 호출 비율이 100% 가 되어 회로가 열렸고, 발급이 7,281건에서 295건으로 떨어졌다.
 * CPU 는 두 경우가 같았다 (88% 대 86%). 이것은 CPU 문제가 아니라 할당 문제다.
 */
public final class CouponCircuits {

    private CouponCircuits() {
    }

    /**
     * Redis 호출용 회로를 만든다. 이 회로는 무엇이 나오든 실패로 센다.
     *
     * <p>순번 확보에서 나올 수 있는 예외가 Redis 장애뿐이라 가릴 것이 없다. 스크립트와 코드가
     * 어긋난 경우는 {@code IllegalStateException} 인데, 그것은 회로가 열려서 가려질 것이 아니라
     * 배포 전에 잡혀야 한다.
     */
    public static CircuitBreaker forRedis(MeterRegistry meters,
                                          CouponCircuitProperties.Settings settings) {
        return bind(meters, CircuitBreaker.of("couponSeq", baseConfig(settings).build()));
    }

    /**
     * DB 쓰기용 회로를 만든다. <b>무엇을 실패로 세느냐가 이 회로의 요점이다.</b>
     *
     * <pre>
     * DuplicateKeyException      안 센다.  선착순에서 정상적으로 나오는 결과다
     * BadSqlGrammarException     안 센다.  버그다.  세면 회로가 열린 뒤 영영 안 닫힌다
     * 일시적 DataAccessException  센다.    DB 가 죽었거나 답하지 않는다
     * </pre>
     *
     * <p>중복 키를 세면 <b>이벤트가 정상일 때 회로가 열린다.</b> 재시도와 매핑 유실로 중복이
     * 꾸준히 나오는 것이 이 설계의 정상 동작이기 때문이다.
     */
    public static CircuitBreaker forDatabaseWrite(MeterRegistry meters,
                                                  CouponCircuitProperties.Settings settings) {
        return bind(meters, CircuitBreaker.of("couponWrite", baseConfig(settings)
                .recordException(CouponCircuits::countsAsOutage)
                .build()));
    }

    /*
     * 지표를 붙인다. 이름과 태그는 resilience4j 의 Micrometer 바인더와 똑같이 맞춘다.
     * 카카오 회로들이 그 바인더로 나가고 있어, 이름이 어긋나면 대시보드가 둘로 갈린다.
     *
     * 전부 폴링이다. 값을 회로에 물어보는 것이라 호출 경로에 아무것도 얹지 않는다.
     * 바인더의 resilience4j_circuitbreaker_calls 타이머는 이벤트로 채워지므로 여기서 뺐다.
     */
    private static CircuitBreaker bind(MeterRegistry meters, CircuitBreaker circuit) {
        if (meters == null) {
            return circuit;
        }
        for (CircuitBreaker.State state : CircuitBreaker.State.values()) {
            Gauge.builder("resilience4j.circuitbreaker.state", circuit,
                            c -> c.getState() == state ? 1.0 : 0.0)
                    .tag("name", circuit.getName())
                    .tag("state", state.name().toLowerCase())
                    .register(meters);
        }
        gauge(meters, circuit, "resilience4j.circuitbreaker.failure.rate",
                c -> c.getMetrics().getFailureRate());
        gauge(meters, circuit, "resilience4j.circuitbreaker.slow.call.rate",
                c -> c.getMetrics().getSlowCallRate());
        gauge(meters, circuit, "resilience4j.circuitbreaker.buffered.calls",
                c -> c.getMetrics().getNumberOfBufferedCalls());
        gauge(meters, circuit, "resilience4j.circuitbreaker.slow.calls",
                c -> c.getMetrics().getNumberOfSlowCalls());

        /*
         * 회로가 열려 있어 대상까지 가지도 않고 끊은 호출이다.
         * 단조 증가라 게이지가 아니라 카운터로 낸다. 그래야 Prometheus 에서 rate() 가 맞는다.
         */
        FunctionCounter.builder("resilience4j.circuitbreaker.not.permitted.calls", circuit,
                        c -> c.getMetrics().getNumberOfNotPermittedCalls())
                .tag("name", circuit.getName())
                .register(meters);
        return circuit;
    }

    private static void gauge(MeterRegistry meters, CircuitBreaker circuit, String name,
                              ToDoubleFunction<CircuitBreaker> value) {
        Gauge.builder(name, circuit, value).tag("name", circuit.getName()).register(meters);
    }

    private static boolean countsAsOutage(Throwable e) {
        return e instanceof DataAccessException dataAccess && DataAccessFailures.isTransient(dataAccess);
    }

    /*
     * 느린 호출도 실패로 센다.
     * 완전히 죽는 것보다 느려지는 쪽이 흔한데, 그때 예외가 안 나면 회로가 안 열린다.
     * 요청 스레드들이 전부 거기서 기다리며 요청 예산을 태우고, 큐는 비어 있는 채로 굶는다.
     *
     * 열림에서 반열림으로 스스로 넘어가게 둔다. 안 그러면 회로가 열린 뒤 아무도 안 부르고,
     * 아무도 안 불러서 시험할 기회가 없는 상태로 굳는다.
     */
    private static CircuitBreakerConfig.Builder baseConfig(CouponCircuitProperties.Settings settings) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitDurationInOpen())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedInHalfOpen())
                .slowCallDurationThreshold(settings.slowCallDuration())
                .slowCallRateThreshold(settings.failureRateThreshold())
                .automaticTransitionFromOpenToHalfOpenEnabled(true);
    }
}
