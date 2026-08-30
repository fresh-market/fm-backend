package com.freshmarket.coupon.domain.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.freshmarket.coupon.domain.CouponCircuitProperties;
import com.freshmarket.coupon.domain.issue.CouponIssueProperties;
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
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        CouponCircuitProperties.Settings seq = new CouponCircuitProperties.Settings(
                50f, 10, MINIMUM_CALLS, WAIT_IN_OPEN, 2, Duration.ofMillis(500));
        sut = new CouponSeqAllocator(redisTemplate, issueProperties,
                new CouponCircuitProperties(seq, seq), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
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
