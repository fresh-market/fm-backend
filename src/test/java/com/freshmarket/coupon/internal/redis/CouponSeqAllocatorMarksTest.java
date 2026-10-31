package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/*
 * 순번 확보가 받은 번호를 기억에 넣는 자리를 본다.
 *
 * 이 기억이 재건에서 카운터의 하한이다. 안 넣으면 그 인스턴스가 쥔 번호가 카운터에 안 반영되어
 * 같은 번호가 다시 나가고, 한쪽이 uk_mc_coupon_seq 에 걸려 결번이 된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("받은 번호 기억")
class CouponSeqAllocatorMarksTest {

    private static final long COUPON_ID = 9001L;
    private static final long MEMBER_ID = 9101L;
    private static final int ISSUE_LIMIT = 10_000;

    @Mock
    private StringRedisTemplate redisTemplate;

    private CouponSeqMarks marks;
    private CouponSeqAllocator sut;

    @BeforeEach
    void 준비() {
        marks = new CouponSeqMarks();
        sut = new CouponSeqAllocator(redisTemplate, 설정(),
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults()), marks);
    }

    @Test
    void 받은_번호를_기억한다() {
        given스크립트가("6");

        sut.allocate(COUPON_ID, MEMBER_ID, ISSUE_LIMIT);

        assertThat(marks.of(COUPON_ID)).isEqualTo(6);
    }

    /*
     * 이미 발급된 회원이 재시도로 같은 번호를 다시 받은 경우도 기억한다.
     *
     * 그 번호도 이 인스턴스가 내준 것이라 지켜야 할 대상이다. 재시도만 남은 마무리 국면에서
     * 이쪽을 빼면 그 인스턴스의 기억이 실제보다 낮아진다.
     */
    @Test
    void 이미_발급된_번호도_기억한다() {
        given스크립트가("6:1");

        sut.allocate(COUPON_ID, MEMBER_ID, ISSUE_LIMIT);

        assertThat(marks.of(COUPON_ID)).isEqualTo(6);
    }

    /*
     * 준비되지 않았다는 답에서는 기억을 버린다.
     *
     * 카운터가 사라진 것이라 이벤트가 끝났거나 전손이다. 안 버리면 같은 쿠폰을 다시 열었을 때
     * 재건이 지난 최댓값까지 카운터를 올려 그만큼의 재고를 통째로 버린다.
     */
    @Test
    void 준비되지_않았다는_답에서는_기억을_버린다() {
        marks.saw(COUPON_ID, 5_887);
        given스크립트가("-2");

        sut.allocate(COUPON_ID, MEMBER_ID, ISSUE_LIMIT);

        assertThat(marks.of(COUPON_ID)).isZero();
    }

    /*
     * 재건 중에는 기억을 안 버린다. 이것이 준비 전(-2)과 가르는 유일한 이유다.
     *
     * 버리면 그 대의 재건이 카운터를 세울 하한을 잃어 bound 가 DB 최댓값으로 떨어지고,
     * 큐에 떠 있던 번호가 다시 나간다.
     */
    @Test
    void 재건_중에는_기억을_안_버린다() {
        marks.saw(COUPON_ID, 5_887);
        given스크립트가("-4");

        sut.allocate(COUPON_ID, MEMBER_ID, ISSUE_LIMIT);

        assertThat(marks.of(COUPON_ID)).isEqualTo(5_887);
    }

    @Test
    void 재건_중은_준비_전과_다른_갈래다() {
        given스크립트가("-4");

        assertThat(sut.allocate(COUPON_ID, MEMBER_ID, ISSUE_LIMIT))
                .isInstanceOf(SeqOutcome.Rebuilding.class);
    }

    // 소진은 키가 살아 있다는 뜻이라 기억을 그대로 둔다
    @Test
    void 소진에서는_기억을_그대로_둔다() {
        marks.saw(COUPON_ID, 10_000);
        given스크립트가("-1");

        sut.allocate(COUPON_ID, MEMBER_ID, ISSUE_LIMIT);

        assertThat(marks.of(COUPON_ID)).isEqualTo(10_000);
    }

    private void given스크립트가(String raw) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(raw);
    }

    private CouponIssueProperties 설정() {
        return new CouponIssueProperties(Duration.ofSeconds(60), Duration.ofMillis(20), 500, 1,
                10_000, Duration.ofSeconds(2), true, Duration.ofSeconds(10),
                Duration.ofSeconds(5), 500,
                Duration.ofSeconds(1), Duration.ofSeconds(5));
    }
}
