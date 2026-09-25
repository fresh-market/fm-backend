package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.repository.CouponRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponSeqRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;

/*
 * 재건에 들어가는 순서를 본다. 무엇을 먼저 보느냐가 이 클래스의 계약이다.
 *
 * 순서가 뒤집히면 조용히 망가진다. 기여가 DB 읽기 뒤에 있으면 DB 에 못 닿는 인스턴스가 거기서
 * 터져 자기 큐를 영영 못 올리는데, 기여는 Redis 와 자기 큐만 건드리므로 그럴 이유가 없다.
 * 안 올린 큐의 번호는 재건이 아무도 안 쥔 것으로 보고 남에게 다시 내준다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponSeqRebuilderEntryTest {

    private static final long COUPON_ID = 9001L;
    private static final String COUNTER = "coupon:9001:counter";
    private static final String REBUILD = "coupon:9001:rebuild";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private MemberCouponSeqRepository seqRepository;

    @Mock
    private CouponSeqInitializer seqInitializer;

    @Mock
    private CouponSeqContributor contributor;

    @Mock
    private CouponSeqInstances instances;

    private CouponSeqRebuilder sut;

    @BeforeEach
    void 준비() {
        sut = new CouponSeqRebuilder(redisTemplate, couponRepository, seqRepository,
                seqInitializer, contributor, instances, 기본_설정(), new SimpleMeterRegistry());
    }

    // 카운터가 서 있으면 멀쩡한 것이다. DB 까지 갈 이유가 없다
    @Test
    void 카운터가_있으면_DB_를_안_본다() {
        given카운터가_있다(true);

        sut.rebuildIfLost(COUPON_ID);

        verifyNoInteractions(couponRepository);
        verify(contributor, never()).contribute(anyLong());
    }

    /*
     * 이 시험이 이 클래스의 핵심이다.
     * 락이 있다는 것은 주도자가 DB 로 이미 판정을 끝냈다는 뜻이라, 기여하는 쪽은 그 판정을
     * 다시 할 이유가 없다. DB 를 보면 DB 장애 때 기여가 통째로 막힌다.
     */
    @Test
    void 재건이_돌고_있으면_DB_없이_기여한다() {
        given카운터가_있다(false);
        given재건_락이_있다(true);

        sut.rebuildIfLost(COUPON_ID);

        verifyNoInteractions(couponRepository);
        verify(contributor).contribute(COUPON_ID);
    }

    /*
     * 2026-09-21 회차가 잡은 것을 고정한다.
     * DB 를 막은 인스턴스 둘의 큐에 825건과 820건이 쌓여 있었는데 한 건도 안 올라갔다.
     */
    @Test
    void DB_가_죽어_있어도_기여는_한다() {
        given카운터가_있다(false);
        given재건_락이_있다(true);
        when(couponRepository.findById(COUPON_ID))
                .thenThrow(new QueryTimeoutException("DB 가 답하지 않는다"));

        assertThatCode(() -> sut.rebuildIfLost(COUPON_ID)).doesNotThrowAnyException();

        verify(contributor).contribute(COUPON_ID);
    }

    /*
     * 주도하려는 쪽은 DB 를 봐야 한다.
     * 관리자가 아직 안 연 이벤트도 카운터가 없어, 그 둘을 가르는 것이 DB 뿐이다.
     */
    @Test
    void 락이_없으면_DB_로_손실과_미개설을_가른다() {
        given카운터가_있다(false);
        given재건_락이_있다(false);
        when(couponRepository.findById(COUPON_ID)).thenReturn(java.util.Optional.empty());

        sut.rebuildIfLost(COUPON_ID);

        verify(couponRepository).findById(COUPON_ID);
        verify(contributor, never()).contribute(anyLong());
    }

    private void given카운터가_있다(boolean exists) {
        when(redisTemplate.hasKey(COUNTER)).thenReturn(exists);
    }

    private void given재건_락이_있다(boolean exists) {
        when(redisTemplate.hasKey(REBUILD)).thenReturn(exists);
    }

    /*
     * 이 시험이 보는 것은 순서뿐이라 나머지 값은 운영 기본값을 그대로 쓴다.
     * rebuildContributeWait 만 짧게 두어 주도자 경로로 새더라도 시험이 안 멈추게 한다.
     */
    private static CouponIssueProperties 기본_설정() {
        return new CouponIssueProperties(
                Duration.ofSeconds(60),
                Duration.ofMillis(20),
                500,
                1,
                Integer.MAX_VALUE,
                Duration.ofSeconds(2),
                Duration.ofMillis(1),
                Duration.ofSeconds(5));
    }
}
