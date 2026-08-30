package com.freshmarket.coupon.domain.issue;

import java.util.concurrent.Callable;

import com.freshmarket.coupon.domain.CouponCircuitProperties;
import com.freshmarket.coupon.domain.CouponCircuits;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * DB 쓰기가 계속 실패하면 이 회로가 열려서 요청 스레드의 순번 확보를 끊는다.
 *
 * <p>DB 가 죽어도 Redis 는 멀쩡해서 순번 확보 회로가 안 열린다. 그대로 두면 요청마다 번호를 받고,
 * 큐에 들어가고, <b>요청 예산을 다 태운 뒤에야 실패한다.</b> 그동안 번호는 계속 나가고 큐는 자란다.
 *
 * <p>세는 쪽과 읽는 쪽이 다른 것이 이 클래스의 요점이다.
 *
 * <pre>
 * 플러시 스레드   이 회로를 통해 쓴다.  성공과 실패가 그대로 집계된다
 * 요청 스레드     상태만 읽는다.  허가를 소비하지 않는다
 * </pre>
 *
 * <p>요청 스레드가 허가를 소비하면 <b>반열림에서 통과시켜 보는 몇 건을 요청 스레드가 다 써버려</b>
 * 정작 플러시가 시험해 볼 기회가 없어진다.
 */
@Component
public class CouponWriteCircuit {

    private final CircuitBreaker circuitBreaker;

    public CouponWriteCircuit(CouponCircuitProperties properties,
                              MeterRegistry meterRegistry) {
        this.circuitBreaker = CouponCircuits.forDatabaseWrite(meterRegistry, properties.write());
    }

    /**
     * 플러시 스레드가 DB 쓰기를 이 메서드로 감싼다. 회로가 열려 있으면 DB 까지 안 가고 곧바로 던진다.
     *
     * <p>회로가 열린 동안의 쓰기를 즉시 거절하는 것이 맞다. 어차피 실패할 쓰기이고, 빨리 거절해야
     * 큐가 비어 메모리가 돌아온다. 그 항목들은 혼잡으로 답하고 <b>번호는 그 사용자 것으로 남는다.</b>
     */
    public <T> T write(Callable<T> write) throws Exception {
        return circuitBreaker.executeCallable(write);
    }

    /**
     * 요청 스레드가 순번을 받아도 되는지 이 메서드로 확인한다.
     *
     * <p><b>"닫힘일 때" 가 아니라 "열림이 아닐 때" 받는다.</b> 닫힘만 받으면 회로가 열린 뒤
     * 새 요청이 안 들어오고, 큐가 비어 플러시가 쓸 것이 없어지고, 아무도 회로를 시험하지 않아
     * 영영 안 닫힌다. 반열림에서 들어온 요청이 플러시를 태워 회로를 시험한다.
     */
    public boolean acceptsWrites() {
        return circuitBreaker.getState() != CircuitBreaker.State.OPEN;
    }
}
