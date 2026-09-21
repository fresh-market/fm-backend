package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.freshmarket.coupon.internal.entity.CouponScope;
import com.freshmarket.coupon.internal.issue.CouponIssueFlusher;
import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.issue.CouponIssueQueue;
import com.freshmarket.coupon.internal.issue.IssueTicket;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/*
 * 이 인스턴스가 쥔 순번을 올리는 자리를 본다.
 *
 * 세 가지가 이 클래스의 계약이다. 큐를 얼리고 나서 훑는가, 남의 쿠폰을 안 섞는가, 무슨 일이
 * 있어도 다시 흐르게 하는가. 셋 중 하나만 어긋나도 재건이 순번의 주인을 잘못 정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponSeqContributorTest {

    private static final long COUPON_ID = 9001L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private CouponIssueQueue queue;

    @Mock
    private CouponIssueFlusher flusher;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private MeterRegistry registry;

    private CouponSeqContributor sut;

    @BeforeEach
    void 준비() {
        registry = new SimpleMeterRegistry();
        sut = new CouponSeqContributor(redisTemplate, queue, flusher, 기본_설정(), registry);
    }

    @Test
    void 자기_큐의_순번을_올린다() {
        given큐에(티켓(9101, 1), 티켓(9102, 2));
        given플러시가_멈춘다();

        sut.contribute(COUPON_ID);

        assertThat(올린_것()).containsOnly(
                Map.entry("9101", "1"),
                Map.entry("9102", "2"));
    }

    // 큐는 쿠폰을 가리지 않고 담는다. 남의 이벤트 순번을 섞어 올리면 그쪽 재건이 틀어진다
    @Test
    void 다른_쿠폰의_티켓은_안_올린다() {
        given큐에(티켓(9101, 1), 다른_쿠폰_티켓(9102, 7));
        given플러시가_멈춘다();

        sut.contribute(COUPON_ID);

        assertThat(올린_것()).containsOnlyKeys("9101");
    }

    /*
     * 이 시험이 이 클래스의 핵심이다.
     * 큐를 얼리지 못하면 훑는 사이에 티켓이 DB 로 내려가고, 재건이 확정된 매핑을 미확정으로 덮어
     * 회수가 그 번호를 남에게 넘긴다. 얼리기에 실패하면 올리지 않는 것이 맞다.
     */
    @Test
    void 큐를_못_얼리면_올리지_않는다() {
        when(flusher.pause(any(Duration.class))).thenReturn(false);

        sut.contribute(COUPON_ID);

        verify(redisTemplate, never()).opsForHash();
        verify(queue, never()).snapshot();
    }

    // 올리다 터져도 큐는 다시 흘러야 한다. 안 그러면 이 인스턴스의 발급이 영영 멈춘다
    @Test
    void 올리다_실패해도_큐를_다시_흐르게_한다() {
        given큐에(티켓(9101, 1));
        given플러시가_멈춘다();
        when(redisTemplate.opsForHash()).thenThrow(new IllegalStateException("Redis 가 답하지 않는다"));

        try {
            sut.contribute(COUPON_ID);
        } catch (RuntimeException ignored) {
            // 이 시험이 보는 것은 예외가 아니라 재개다
        }

        verify(flusher).resume();
    }

    // 올릴 것이 없으면 Redis 를 안 친다
    @Test
    void 큐가_비었으면_아무것도_안_올린다() {
        given큐에();
        given플러시가_멈춘다();

        sut.contribute(COUPON_ID);

        verify(hashOperations, never()).putAll(anyString(), any());
        verify(flusher).resume();
    }

    /*
     * 이 키만 counter 의 만료를 물려받을 자리가 없다.
     * 지우는 것이 재건 주도자의 정리 한 번뿐이라, 그 정리와 락 해제 사이에 올린 기여는 아무도
     * 안 지운다. 시한이 없으면 그 해시가 Redis 에 영영 남는다.
     */
    @Test
    void 올린_큐에_시한을_건다() {
        given큐에(티켓(9101, 1));
        given플러시가_멈춘다();

        sut.contribute(COUPON_ID);

        verify(redisTemplate).expire("coupon:9001:rebuild:queued", Duration.ofSeconds(30));
    }

    /*
     * 올릴 것이 없어도 늦은 정도는 재야 한다.
     * 안 재면 "기여가 안 늦었다" 와 "올릴 것이 없었다" 가 지표에서 같아진다.
     * 2026-09-21 회차가 전부 후자였는데 표본이 0건이라 그 사실을 지표로는 못 봤다.
     */
    @Test
    void 큐가_비어도_늦은_정도는_잰다() {
        given큐에();
        given플러시가_멈춘다();
        given재건이_시작된_지(80);

        sut.contribute(COUPON_ID);

        assertThat(잰_횟수()).isEqualTo(1);
        assertThat(잰_최댓값()).isGreaterThanOrEqualTo(80);
    }

    // 올릴 것이 없으면 키를 안 만들므로 시한도 안 건다
    @Test
    void 큐가_비었으면_시한도_안_건다() {
        given큐에();
        given플러시가_멈춘다();

        sut.contribute(COUPON_ID);

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    /*
     * 이 값이 rebuild-contribute-wait 를 좁히는 근거다.
     * 지금 3초는 GC 정지와 배치 하나를 덮을 만큼으로 고른 값이지 실측으로 좁힌 값이 아니다.
     */
    @Test
    void 늦은_정도를_잰다() {
        given큐에(티켓(9101, 1));
        given플러시가_멈춘다();
        given재건이_시작된_지(120);

        sut.contribute(COUPON_ID);

        assertThat(잰_횟수()).isEqualTo(1);
        assertThat(잰_최댓값()).isGreaterThanOrEqualTo(120);
    }

    /*
     * 배포가 도는 동안에는 시작 시각이 없는 옛 형식의 락이 있을 수 있다.
     * 지표 하나 때문에 기여가 막히면 재건이 그 인스턴스의 번호를 남에게 내준다.
     */
    @Test
    void 락을_못_읽어도_올리기는_끝낸다() {
        given큐에(티켓(9101, 1));
        given플러시가_멈춘다();
        given락_값이("시작-시각-없는-옛-형식");

        sut.contribute(COUPON_ID);

        assertThat(올린_것()).containsOnlyKeys("9101");
        assertThat(잰_횟수()).isZero();
    }

    /*
     * 재는 시점에는 올리기가 이미 끝나 있다.
     * 여기서 터진 것을 밖으로 보내면 플러시 루프가 그 배치를 실패로 처리해, 지표 하나 때문에
     * 멀쩡히 끝난 기여가 오류로 남는다.
     */
    @Test
    void 재다가_터져도_기여는_성공으로_끝낸다() {
        given큐에(티켓(9101, 1));
        given플러시가_멈춘다();
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("Redis 가 답하지 않는다"));

        sut.contribute(COUPON_ID);

        assertThat(올린_것()).containsOnlyKeys("9101");
        verify(flusher).resume();
    }

    private void given플러시가_멈춘다() {
        when(flusher.pause(any(Duration.class))).thenReturn(true);
    }

    private void given재건이_시작된_지(long millis) {
        given락_값이(new RebuildLock("토큰", System.currentTimeMillis() - millis).value());
    }

    private void given락_값이(String raw) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("coupon:9001:rebuild")).thenReturn(raw);
    }

    /*
     * lag 하나로는 rebuild-contribute-wait 를 못 정한다.
     * 그 값이 덮어야 하는 것은 총합인데, 총합이 큰 이유가 "이 인스턴스가 늦게 불렸다" 인지
     * "올리는 데 오래 걸렸다" 인지에 따라 고칠 자리가 정반대다. 그래서 셋으로 가른다.
     */
    @Test
    void 기여_시간을_셋으로_가른다() {
        given큐에(티켓(9101, 1));
        given플러시가_멈춘다();
        given재건이_시작된_지(120);

        sut.contribute(COUPON_ID);

        assertThat(registry.timer("coupon.seq.rebuild.contribute.pause").count()).isEqualTo(1);
        assertThat(registry.timer("coupon.seq.rebuild.contribute.write").count()).isEqualTo(1);
        assertThat(잰_횟수()).isEqualTo(1);
    }

    // 올릴 것이 없어도 세 구간을 다 잰다. 안 그러면 빈 기여가 분포에서 빠진다
    @Test
    void 큐가_비어도_셋을_다_잰다() {
        given큐에();
        given플러시가_멈춘다();
        given재건이_시작된_지(50);

        sut.contribute(COUPON_ID);

        assertThat(registry.timer("coupon.seq.rebuild.contribute.pause").count()).isEqualTo(1);
        assertThat(registry.timer("coupon.seq.rebuild.contribute.write").count()).isEqualTo(1);
    }

    // 큐를 못 얼렸으면 올리지도 않았으므로 쓴 시간이 없다
    @Test
    void 큐를_못_얼리면_쓴_시간을_안_잰다() {
        when(flusher.pause(any(Duration.class))).thenReturn(false);

        sut.contribute(COUPON_ID);

        assertThat(registry.timer("coupon.seq.rebuild.contribute.write").count()).isZero();
        assertThat(registry.timer("coupon.seq.rebuild.contribute.pause").count()).isZero();
    }

    private long 잰_횟수() {
        return registry.timer("coupon.seq.rebuild.contribute.lag").count();
    }

    private double 잰_최댓값() {
        return registry.timer("coupon.seq.rebuild.contribute.lag").max(TimeUnit.MILLISECONDS);
    }

    /*
     * 시한은 재건 락과 같은 수명이라 rebuildContributeWait 의 열 배다.
     * 나머지 값은 이 시험이 안 보므로 운영 기본값을 그대로 쓴다.
     */
    private static CouponIssueProperties 기본_설정() {
        return new CouponIssueProperties(
                Duration.ofSeconds(60),
                Duration.ofMillis(20),
                500,
                1,
                Integer.MAX_VALUE,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ofSeconds(5));
    }

    private void given큐에(IssueTicket... tickets) {
        when(queue.snapshot()).thenReturn(List.of(tickets));
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> 올린_것() {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq("coupon:9001:rebuild:queued"), captor.capture());
        return captor.getValue();
    }

    private static IssueTicket 티켓(long memberId, int issueSeq) {
        return IssueTicket.of(COUPON_ID, memberId, CouponScope.ORDER, 100, issueSeq);
    }

    private static IssueTicket 다른_쿠폰_티켓(long memberId, int issueSeq) {
        return IssueTicket.of(COUPON_ID + 1, memberId, CouponScope.ORDER, 100, issueSeq);
    }
}
