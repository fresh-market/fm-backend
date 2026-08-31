package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/*
 * Redis 가 연속해서 깨졌을 때 회로가 열리고 다시 닫히는지 본다.
 *
 * 여기서 확인하는 것은 "실패하면 예외가 난다" 가 아니라 "열린 뒤에는 Redis 로 아예 안 보낸다" 다.
 * 그것이 회로를 두는 이유이고, 안 그러면 요청 스레드들이 죽은 Redis 를 계속 두드리며 요청 예산을
 * 태운다.
 */
@ExtendWith(MockitoExtension.class)
class CouponSeqAllocatorCircuitTest {

    private static final long COUPON_ID = 77L;
    private static final int ISSUE_LIMIT = 100;

    // 이만큼 쌓여야 비율을 재기 시작한다. 시험을 짧게 하려고 작게 잡는다
    private static final int MINIMUM_CALLS = 5;
    private static final Duration WAIT_IN_OPEN = Duration.ofMillis(200);

    @Mock
    private StringRedisTemplate redisTemplate;

    private CouponSeqAllocator sut;

    @BeforeEach
    void setUp() {
        CouponIssueProperties issueProperties = new CouponIssueProperties(
                Duration.ofSeconds(60), Duration.ofMillis(20), 500, 1, 10_000,
                Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5));
        /*
         * 운영 값은 application.yml 이 갖는다. 여기서는 시험이 짧게 끝나도록 작게 잡은
         * 설정으로 레지스트리를 만들어 넣는다. 이름이 couponSeq 여야 코드가 그것을 집는다.
         */
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
                .slowCallDurationThreshold(Duration.ofMillis(500))
                .build());
        sut = new CouponSeqAllocator(redisTemplate, issueProperties, registry);
        /*
         * 운영에서는 이 회로가 METRICS_ONLY 로 떠서 호출을 안 막는다 (CouponSeqAllocator 참조).
         * 아래 시험들은 회로가 열리는 동작 자체를 보는 것이라 여기서 닫힘으로 되돌린다.
         * 켜기로 결정이 바뀌면 이 줄을 지우고 시험이 그대로 통과해야 한다.
         */
        registry.circuitBreaker("couponSeq").transitionToClosedState();
    }

    /*
     * 이 회로는 지금 호출을 막지 않는다. 재서 그렇게 정했고 근거는 CouponSeqAllocator 에 있다.
     *
     * DISABLED 가 아니라 METRICS_ONLY 인 것이 요점이다. 둘 다 안 막지만 이쪽은 실패율과 느림
     * 비율을 계속 기록해서, 나중에 "이제 켜도 되는가" 를 판단할 근거가 남는다.
     */
    @Test
    void 순번_회로는_호출을_막지_않는다() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        CouponIssueProperties properties = new CouponIssueProperties(
                Duration.ofSeconds(60), Duration.ofMillis(20), 500, 1, 10_000,
                Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5));

        new CouponSeqAllocator(redisTemplate, properties, registry);

        assertThat(registry.circuitBreaker("couponSeq").getState())
                .isEqualTo(CircuitBreaker.State.METRICS_ONLY);
    }

    @Test
    void Redis_가_답하지_않으면_순번을_못_준다고_던진다() {
        // given
        givenRedisFails();

        // when, then
        assertThatThrownBy(() -> sut.allocate(COUPON_ID, 1L, ISSUE_LIMIT))
                .isInstanceOf(CouponSeqUnavailableException.class);
    }

    /*
     * 회로가 열리면 요청 스레드가 Redis 를 안 거치고 그 자리에서 돌아온다.
     * 죽은 Redis 를 계속 두드리면 그 대기가 요청 예산을 태우고, 그동안 톰캣 스레드가 잡힌다.
     */
    @Test
    void 연속해서_깨지면_회로가_열려_Redis_를_안_부른다() {
        // given 최소 건수만큼 실패시킨다
        givenRedisFails();
        for (int i = 0; i < MINIMUM_CALLS; i++) {
            assertThatThrownBy(() -> sut.allocate(COUPON_ID, 1L, ISSUE_LIMIT))
                    .isInstanceOf(CouponSeqUnavailableException.class);
        }

        // when 그 뒤에 더 부른다
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> sut.allocate(COUPON_ID, 1L, ISSUE_LIMIT))
                    .isInstanceOf(CouponSeqUnavailableException.class);
        }

        // then 열린 뒤의 셋은 Redis 까지 안 갔다
        verify(redisTemplate, times(MINIMUM_CALLS))
                .execute(any(RedisScript.class), anyList(), any(), any(), any());
    }

    /*
     * 회로가 다시 닫히지 않으면 Redis 가 살아나도 이벤트가 죽은 채로 끝난다.
     * 열림에서 반열림으로 넘어가 통과시켜 본 호출이 성공하면 닫힌다.
     */
    @Test
    void 기다린_뒤_성공하면_회로가_다시_닫힌다() throws Exception {
        // given 회로를 연다
        givenRedisFails();
        for (int i = 0; i < MINIMUM_CALLS; i++) {
            assertThatThrownBy(() -> sut.allocate(COUPON_ID, 1L, ISSUE_LIMIT))
                    .isInstanceOf(CouponSeqUnavailableException.class);
        }

        // when 열림 대기가 지난 뒤 Redis 가 살아난다
        Thread.sleep(WAIT_IN_OPEN.toMillis() * 2);
        reset(redisTemplate);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn("1");

        // then 반열림에서 통과시킨 호출이 성공해 회로가 닫힌다
        assertThat(sut.allocate(COUPON_ID, 1L, ISSUE_LIMIT)).isEqualTo(new SeqOutcome.Allocated(1));
        assertThat(sut.allocate(COUPON_ID, 2L, ISSUE_LIMIT)).isEqualTo(new SeqOutcome.Allocated(1));
        assertThat(sut.allocate(COUPON_ID, 3L, ISSUE_LIMIT)).isEqualTo(new SeqOutcome.Allocated(1));
    }

    private void givenRedisFails() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("Redis 가 답하지 않는다"));
    }
}
