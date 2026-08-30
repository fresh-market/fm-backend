package com.freshmarket.coupon.domain.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.concurrent.Callable;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;

/*
 * DB 쓰기가 계속 실패하면 순번 확보를 끊는 회로다.
 *
 * 여기서 확인하는 것은 "실패하면 열린다" 가 아니라 무엇을 실패로 세느냐다. 중복 키까지 세면
 * 이벤트가 정상일 때 회로가 열리고, 버그까지 세면 회로가 열린 뒤 영영 안 닫힌다.
 */
class CouponWriteCircuitTest {

    // 이만큼 쌓여야 비율을 재기 시작한다. 시험을 짧게 하려고 작게 잡는다
    private static final int MINIMUM_CALLS = 5;
    private static final Duration WAIT_IN_OPEN = Duration.ofMillis(200);

    private CouponWriteCircuit sut;

    @BeforeEach
    void setUp() {
        // 운영 값은 application.yml 이 갖는다. 여기서는 시험이 짧게 끝나도록 작게 잡는다
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(MINIMUM_CALLS)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(WAIT_IN_OPEN)
                .permittedNumberOfCallsInHalfOpenState(2)
                // 운영과 같게 둔다. 안 켜면 다음 호출이 올 때까지 OPEN 인 채로 남아
                // getState() 로 회복을 못 본다
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .recordExceptions(org.springframework.dao.DataAccessResourceFailureException.class,
                        org.springframework.dao.TransientDataAccessException.class,
                        org.springframework.dao.RecoverableDataAccessException.class)
                .build());
        sut = new CouponWriteCircuit(registry);
    }

    @Test
    void 처음에는_쓰기를_받는다() {
        assertThat(sut.acceptsWrites()).isTrue();
    }

    /*
     * 이 회로의 요점이다.
     * 재시도와 매핑 유실로 중복이 꾸준히 나오는 것이 이 설계의 정상 동작이라, 그것을 세면
     * 이벤트가 멀쩡할 때 회로가 열려 발급이 통째로 멈춘다.
     */
    @Test
    void 중복_키는_회로를_열지_않는다() {
        연속으로_실패시킨다(new DuplicateKeyException("이미 받은 회원이다"));

        assertThat(sut.acceptsWrites()).isTrue();
    }

    // 버그는 재시도해도 같다. 세면 회로가 열린 뒤 아무도 못 닫는다
    @Test
    void SQL_오류는_회로를_열지_않는다() {
        연속으로_실패시킨다(new BadSqlGrammarException("발급", "INSERT ...", new SQLException()));

        assertThat(sut.acceptsWrites()).isTrue();
    }

    @Test
    void DB_가_답하지_않으면_회로가_열린다() {
        연속으로_실패시킨다(new QueryTimeoutException("DB 가 답하지 않는다"));

        assertThat(sut.acceptsWrites()).isFalse();
    }

    // 회로가 열리면 플러시의 쓰기도 DB 까지 안 간다. 어차피 실패할 쓰기라 빨리 거절하는 편이 낫다
    @Test
    void 회로가_열리면_쓰기가_DB_까지_안_간다() {
        연속으로_실패시킨다(new QueryTimeoutException("DB 가 답하지 않는다"));

        assertThatThrownBy(() -> sut.write(() -> {
            throw new AssertionError("여기까지 오면 안 된다");
        })).isInstanceOf(CallNotPermittedException.class);
    }

    /*
     * 반열림에서 요청을 다시 받아야 한다.
     * 닫힘일 때만 받으면 회로가 열린 뒤 새 요청이 안 들어오고, 큐가 비어 플러시가 쓸 것이 없고,
     * 아무도 회로를 시험하지 않아 영영 안 닫힌다.
     */
    @Test
    void 기다리면_다시_받기_시작한다() throws Exception {
        연속으로_실패시킨다(new QueryTimeoutException("DB 가 답하지 않는다"));
        assertThat(sut.acceptsWrites()).isFalse();

        Thread.sleep(WAIT_IN_OPEN.toMillis() * 2);

        assertThat(sut.acceptsWrites()).isTrue();
    }

    @Test
    void 성공한_쓰기는_그대로_돌려준다() throws Exception {
        assertThat(sut.write(() -> "썼다")).isEqualTo("썼다");
    }

    private void 연속으로_실패시킨다(DataAccessException failure) {
        Callable<Void> 실패하는_쓰기 = () -> {
            throw failure;
        };
        for (int i = 0; i < MINIMUM_CALLS; i++) {
            try {
                sut.write(실패하는_쓰기);
            } catch (Exception ignored) {
                // 회로가 실패를 세게 하는 것이 목적이라 여기서 볼 것이 없다
            }
        }
    }
}
