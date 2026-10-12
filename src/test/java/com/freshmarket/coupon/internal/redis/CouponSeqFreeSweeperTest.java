package com.freshmarket.coupon.internal.redis;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.repository.CouponConsistencyRepository;
import com.freshmarket.coupon.internal.audit.CouponIssueCount;
import com.freshmarket.coupon.internal.repository.CouponRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 쓸기가 어느 번호를 후보로 고르는지 본다. 고르는 조건이 이 클래스의 계약 전부다.
 *
 * 넣는 쪽은 스크립트가 맡고 단위 시험으로는 못 본다. seq 해시를 통째로 읽어 가리는 일이라
 * 목으로 재면 목에게 시킨 것을 그대로 돌려받는다. 그쪽은 통합 시험이 실물로 본다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("주인 잃은 번호 쓸기")
class CouponSeqFreeSweeperTest {

    private static final long COUPON_ID = 9001L;
    private static final String COUNTER = "coupon:9001:counter";
    private static final int MARGIN = 100;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private CouponConsistencyRepository consistencyRepository;

    private CouponSeqFreeSweeper sut;

    @BeforeEach
    void 준비() {
        CouponSeqRebuildRedis rebuildRedis = mock(CouponSeqRebuildRedis.class);
        when(rebuildRedis.template()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(couponRepository.findOpenLimitedEvents()).thenReturn(List.of(COUPON_ID));
        sut = new CouponSeqFreeSweeper(rebuildRedis, couponRepository, consistencyRepository,
                설정(), new SimpleMeterRegistry());
    }

    /*
     * 개수와 최댓값이 같으면 결번이 없다.
     *
     * 순번이 쿠폰마다 유일하므로 그 둘이 같다는 것이 곧 1..최댓값이 빈틈없이 다 있다는 뜻이다.
     * 정상 운영의 대부분이 이 경로이므로 여기서 무거운 조회를 안 타야 한다.
     */
    @Test
    void 결번이_없으면_전체_목록을_안_읽는다() {
        given카운터가("5000");
        given최댓값과_행수(4000, 4000);

        sut.sweep();

        verify(consistencyRepository, never()).findIssueSeqs(anyLong());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    /*
     * 이 시험이 설계의 중심을 고정한다.
     *
     * 시각을 들고 있는 것이 없어 카운터와의 거리로 나이를 잰다. 거리가 가까운 번호는 방금 나가
     * 아직 큐에 떠 있는 것이라, 그것을 되살리면 살아 있는 티켓의 번호를 남에게 내준다.
     */
    @Test
    void 카운터에_가까운_번호는_후보가_아니다() {
        given카운터가("1060");
        given최댓값과_행수(999, 996);
        // 기준이 100 이라 1060 - seq >= 100, 곧 960 이하만 후보다. 셋 중 950 만 든다
        when(consistencyRepository.findIssueSeqs(COUPON_ID)).thenReturn(발급된_번호(999, 950, 980, 995));

        sut.sweep();

        assertThat(넘긴_번호()).containsExactly("950");
    }

    @Test
    void 멀리_떨어진_결번은_모두_후보다() {
        given카운터가("5000");
        given최댓값과_행수(3000, 2997);
        when(consistencyRepository.findIssueSeqs(COUPON_ID)).thenReturn(발급된_번호(3000, 100, 200, 300));

        sut.sweep();

        assertThat(넘긴_번호()).containsExactly("100", "200", "300");
    }

    // 카운터가 없으면 이벤트가 아니다. 안 열린 쿠폰에 번호를 넣으면 그것이 먼저 나간다
    @Test
    void 카운터가_없으면_아무것도_안_한다() {
        given카운터가(null);

        sut.sweep();

        verify(consistencyRepository, never()).findMaxIssueSeq(anyLong());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    // 발급이 하나도 없으면 되살릴 것도 없다
    @Test
    void 발급이_없으면_아무것도_안_한다() {
        given카운터가("5000");
        when(consistencyRepository.findMaxIssueSeq(COUPON_ID)).thenReturn(Optional.empty());

        sut.sweep();

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    // 후보가 하나도 안 남으면 Redis 를 안 친다. 거리 조건이 전부 걸러낸 경우다
    @Test
    void 후보가_없으면_Redis_를_안_친다() {
        // 결번이 998 하나인데 1000 - 998 = 2 라 기준 100 을 못 넘는다
        given카운터가("1000");
        given최댓값과_행수(999, 998);
        when(consistencyRepository.findIssueSeqs(COUPON_ID)).thenReturn(발급된_번호(999, 998));

        sut.sweep();

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    /*
     * 카운터가 숫자가 아니어도 안 터진다.
     * 이 배치는 되살리기라, 한 쿠폰의 값이 망가졌다고 다른 쿠폰의 재고를 묶어 둘 이유가 없다.
     */
    @Test
    void 카운터가_숫자가_아니어도_안_터진다() {
        given카운터가("망가진값");

        sut.sweep();

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    private List<String> 넘긴_번호() {
        ArgumentCaptor<Object[]> argv = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), argv.capture());
        return List.of(argv.getValue()).stream().map(String::valueOf).toList();
    }

    private void given카운터가(String value) {
        when(valueOperations.get(COUNTER)).thenReturn(value);
    }

    private void given최댓값과_행수(int maxSeq, long rows) {
        when(consistencyRepository.findMaxIssueSeq(COUPON_ID)).thenReturn(Optional.of(maxSeq));
        when(consistencyRepository.findIssueCount(COUPON_ID))
                .thenReturn(Optional.of(new CouponIssueCount(COUPON_ID, rows, 10_000, rows)));
    }

    /* 최댓값까지 다 발급된 것으로 두고 뺄 번호만 받는다. 시험이 결번을 직접 적게 하는 것이 읽기 쉽다 */
    private List<Integer> 발급된_번호(int maxSeq, int... gaps) {
        boolean[] gap = new boolean[maxSeq + 1];
        for (int g : gaps) {
            gap[g] = true;
        }
        return java.util.stream.IntStream.rangeClosed(1, maxSeq).filter(seq -> !gap[seq]).boxed().toList();
    }

    private CouponIssueProperties 설정() {
        return new CouponIssueProperties(Duration.ofSeconds(60), Duration.ofMillis(20), 500, 1,
                10_000, Duration.ofSeconds(2), Duration.ofSeconds(5), MARGIN);
    }
}
