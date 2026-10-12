package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/*
 * 요청 경로가 재건을 몇 번 띄우는지를 본다.
 *
 * 이벤트가 열리는 순간에는 수만 개가 같은 응답을 받으므로, 그 수만큼 재건이 뜨면 그것이 곧
 * 장애가 된다. 이 클래스가 지켜야 하는 성질은 "몰려도 하나" 와 "실패하면 다음이 다시 뜬다" 둘이다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponSeqRebuildTriggerTest {

    private static final long COUPON_ID = 9001L;
    private static final String COUNTER = "coupon:9001:counter";

    @Mock
    private CouponSeqRebuilder rebuilder;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private MeterRegistry registry;

    @BeforeEach
    void 준비() {
        registry = new SimpleMeterRegistry();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private CouponSeqRebuildTrigger 트리거() {
        return new CouponSeqRebuildTrigger(rebuilder, redisTemplate, new CouponSeqMarks(), registry);
    }

    private void given카운터가(String value) {
        when(valueOperations.get(COUNTER)).thenReturn(value);
    }

    private long 뒤처짐_표본() {
        return registry.summary("coupon.seq.counter.behind").count();
    }

    private double 뒤처짐_최댓값() {
        return registry.summary("coupon.seq.counter.behind").max();
    }

    @Test
    void 한_번_부르면_재건이_한_번_뜬다() {
        CouponSeqRebuildTrigger sut = 트리거();

        sut.suspect(COUPON_ID);

        verify(rebuilder, timeout(5_000)).rebuild(COUPON_ID);
    }

    /*
     * 이 시험이 이 클래스의 핵심이다.
     * 재건이 도는 동안 들어온 요청들은 집합에서 걸러져 작업을 안 띄워야 한다.
     */
    @Test
    void 재건이_도는_동안_몰려도_한_번만_뜬다() throws Exception {
        CountDownLatch 재건이_시작됐다 = new CountDownLatch(1);
        CountDownLatch 재건을_붙잡는다 = new CountDownLatch(1);
        doAnswer(invocation -> {
            재건이_시작됐다.countDown();
            재건을_붙잡는다.await(5, TimeUnit.SECONDS);
            return null;
        }).when(rebuilder).rebuild(anyLong());

        CouponSeqRebuildTrigger sut = 트리거();

        // when 첫 요청이 재건을 띄우고, 그것이 도는 동안 이천 개가 더 들어온다
        sut.suspect(COUPON_ID);
        assertThat(재건이_시작됐다.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 2_000; i++) {
            sut.suspect(COUPON_ID);
        }
        재건을_붙잡는다.countDown();

        // then
        verify(rebuilder, timeout(5_000).times(1)).rebuild(COUPON_ID);
    }

    /*
     * 재건이 실패하면 표시가 지워져야 한다.
     * 안 지우면 그 쿠폰은 이 인스턴스가 살아 있는 동안 영영 재건되지 않는다.
     */
    @Test
    void 재건이_실패하면_다음_요청이_다시_띄운다() throws Exception {
        doThrow(new IllegalStateException("DB 가 답하지 않는다"))
                .when(rebuilder).rebuild(anyLong());
        CouponSeqRebuildTrigger sut = 트리거();

        /*
         * 이벤트가 도는 동안 요청이 계속 들어오는 모양을 그대로 흉내 낸다.
         * 표시가 지워지는 것은 재건이 끝난 뒤라, 한 번 부르고 곧바로 다시 부르면 아직 도는 중일
         * 수 있다. 그때는 걸러지는 것이 맞는 동작이고, 그다음 요청이 띄운다.
         */
        for (int i = 0; i < 5; i++) {
            sut.suspect(COUPON_ID);
            Thread.sleep(50);
        }

        // 몇 번인지는 정하지 않는다. 이 시험이 지키려는 것은 "한 번 실패하면 끝" 이 아니라는 것뿐이다
        verify(rebuilder, timeout(5_000).atLeast(2)).rebuild(COUPON_ID);
    }

    /*
     * 한 번 실패하고 끝내면 재연결 경로에서 재건이 영영 안 돈다.
     *
     * 요청 경로에는 "다음 요청" 이 있지만 재연결 감시는 재연결마다 한 번만 깨우므로 그 다음이
     * 없다. 2026-10-10 J-2 회차에서 재건이 Redis 타임아웃 한 번으로 끝나 재고 10,000장 중
     * 1,030장이 안 나갔다.
     */
    @Test
    void 재건이_실패하면_그_자리에서_다시_해_본다() {
        doThrow(new IllegalStateException("승격 직후라 느리다"))
                .doThrow(new IllegalStateException("아직 느리다"))
                .doNothing()
                .when(rebuilder).rebuild(anyLong());
        CouponSeqRebuildTrigger sut = 트리거();

        // when 한 번만 부른다. 다음 요청이 띄워 주기를 기대하지 않는다
        sut.suspect(COUPON_ID);

        verify(rebuilder, timeout(5_000).times(3)).rebuild(COUPON_ID);
    }

    /*
     * 재시도에 상한이 없으면 안 된다. 승격이 아니라 설정이 틀린 경우에는 몇 번을 해도 실패하고,
     * 그 스레드가 영영 안 끝나면 다음 신호를 받을 일꾼이 없다.
     */
    @Test
    void 계속_실패하면_세_번에서_그만둔다() {
        doThrow(new IllegalStateException("무엇을 해도 실패한다"))
                .when(rebuilder).rebuild(anyLong());
        CouponSeqRebuildTrigger sut = 트리거();

        sut.suspect(COUPON_ID);

        verify(rebuilder, timeout(5_000).times(3)).rebuild(COUPON_ID);
        verify(rebuilder, never()).rebuild(COUPON_ID + 1);
    }

    /*
     * 플러시 뒤 확인이 Redis 실패를 삼키는지 본다.
     *
     * 안 삼키면 플러시 루프의 바깥 catch 가 방금 성공한 배치를 실패로 처리한다. Redis 가 죽어도
     * 큐에 든 발급은 끝까지 간다는 성질(coupon.md 8장)이 이 한 줄에 걸려 있다.
     */
    @Test
    void 플러시_뒤_확인은_Redis_실패를_삼킨다() {
        when(valueOperations.get(COUNTER))
                .thenThrow(new RedisConnectionFailureException("Redis 가 답하지 않는다"));
        CouponSeqRebuildTrigger sut = 트리거();

        assertThatCode(() -> sut.checkAfterFlush(COUPON_ID, 10)).doesNotThrowAnyException();

        verifyNoInteractions(rebuilder);
    }

    /*
     * counter >= 나간 어느 번호 는 깨질 수 없는 식이다.
     * 번호는 counter 를 INCR 한 뒤에야 나가고 counter 는 정상적으로 줄지 않는다.
     */
    @Test
    void 카운터가_배치보다_크면_아무것도_안_잰다() {
        given카운터가("500");

        트리거().checkAfterFlush(COUPON_ID, 480);

        assertThat(뒤처짐_표본()).isZero();
    }

    /*
     * 재건이 살아 있는 티켓보다 낮게 문을 연 경우다.
     * 기여를 못 한 인스턴스가 쥔 번호는 재건이 세운 카운터보다 클 수 있다.
     */
    @Test
    void 카운터가_방금_쓴_배치보다_작으면_잰다() {
        given카운터가("4000");

        트리거().checkAfterFlush(COUPON_ID, 5000);

        assertThat(뒤처짐_표본()).isEqualTo(1);
        assertThat(뒤처짐_최댓값()).isEqualTo(1000);
    }

    /*
     * 이 시험이 이 계측의 핵심이다.
     * 복제가 밀린 채 승격되면 카운터가 뒤로 가는데, 그때 나가는 번호는 줄어든 카운터에서
     * 나오므로 배치끼리만 견주면 안 잡힌다. 여태 본 최댓값을 들고 있어야 드러난다.
     */
    @Test
    void 카운터가_뒤로_가면_여태_본_최댓값으로_잡는다() {
        CouponSeqRebuildTrigger sut = 트리거();
        given카운터가("2790");
        sut.checkAfterFlush(COUPON_ID, 2780);
        assertThat(뒤처짐_표본()).isZero();

        // 복제 꼬리 유실. 카운터가 200 뒤로 가고 그 뒤 번호는 줄어든 값에서 나온다
        given카운터가("2600");

        sut.checkAfterFlush(COUPON_ID, 2600);

        assertThat(뒤처짐_표본()).isEqualTo(1);
        assertThat(뒤처짐_최댓값()).isEqualTo(180);
    }

    // 카운터가 없으면 견줄 대상이 아니다. 전손이라 재는 대신 복구를 띄운다
    @Test
    void 카운터가_없으면_안_잰다() {
        given카운터가(null);

        트리거().checkAfterFlush(COUPON_ID, 5000);

        assertThat(뒤처짐_표본()).isZero();
    }

    /*
     * 이 시험이 2026-10-12 재설계에서 내가 지웠다 되살린 자리를 고정한다.
     *
     * 복구를 띄우는 자리를 요청 경로 하나로 줄였더니, ALB 가 요청을 안 보내는 인스턴스는 손실을
     * 영영 모르게 됐다. 그 대가 자기 큐를 seq 에 못 되살리면 그 회원들의 재시도가 번호를 하나씩
     * 더 태우고 uk_mc_coupon_member 에 걸린다.
     *
     * 티켓을 쥔 인스턴스는 그것을 쓰느라 반드시 이 자리를 지나므로 여기서 알아챌 수 있다.
     */
    @Test
    void 카운터가_없으면_복구를_띄운다() {
        given카운터가(null);

        트리거().checkAfterFlush(COUPON_ID, 5000);

        verify(rebuilder, timeout(5_000)).rebuild(COUPON_ID);
    }

    /*
     * 뒤처짐을 재기만 하지 않고 올린다.
     *
     * 보통은 순번 확보가 다음 요청에서 스스로 고치는데, 요청을 안 받는 인스턴스는 그 기회가 없다.
     * 그동안 이 대가 쥔 번호를 남들이 다시 내주므로 한쪽이 uk_mc_coupon_seq 에 걸린다.
     */
    @Test
    void 뒤처지면_카운터를_올린다() {
        given카운터가("4000");

        트리거().checkAfterFlush(COUPON_ID, 5000);

        verify(rebuilder).raiseCounter(COUPON_ID, 5000);
    }

    // 뒤처지지 않았으면 쓰지 않는다. 배치마다 도는 자리라 여기서 새면 쓰기가 초당 백 번이다
    @Test
    void 뒤처지지_않으면_안_올린다() {
        given카운터가("5000");

        트리거().checkAfterFlush(COUPON_ID, 4800);

        verify(rebuilder, never()).raiseCounter(anyLong(), anyInt());
    }

    // 재는 데 실패해도 방금 쓴 배치는 이미 성공한 것이다
    @Test
    void 카운터가_숫자가_아니어도_안_터진다() {
        given카운터가("망가진값");

        assertThatCode(() -> 트리거().checkAfterFlush(COUPON_ID, 10)).doesNotThrowAnyException();
        assertThat(뒤처짐_표본()).isZero();
    }

    // 쿠폰이 다르면 서로를 막지 않는다. 집합의 키가 쿠폰이라는 뜻이다
    @Test
    void 다른_쿠폰은_서로를_막지_않는다() {
        CouponSeqRebuildTrigger sut = 트리거();

        sut.suspect(COUPON_ID);
        sut.suspect(COUPON_ID + 1);

        verify(rebuilder, timeout(5_000)).rebuild(COUPON_ID);
        verify(rebuilder, timeout(5_000)).rebuild(COUPON_ID + 1);
        verify(rebuilder, times(2)).rebuild(anyLong());
    }
}
