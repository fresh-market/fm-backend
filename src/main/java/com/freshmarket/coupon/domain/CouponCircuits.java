package com.freshmarket.coupon.domain;

import com.freshmarket.coupon.domain.exception.DataAccessFailures;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.dao.DataAccessException;

/**
 * 이 클래스가 회로 둘을 같은 규칙으로 만든다. 무엇을 실패로 세느냐만 다르고 나머지 모양은 같다.
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
    public static CircuitBreaker forRedis(CouponCircuitProperties.Settings settings) {
        return CircuitBreaker.of("couponSeq", baseConfig(settings).build());
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
    public static CircuitBreaker forDatabaseWrite(CouponCircuitProperties.Settings settings) {
        return CircuitBreaker.of("couponWrite", baseConfig(settings)
                .recordException(CouponCircuits::countsAsOutage)
                .build());
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
