package com.freshmarket.coupon.domain.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/*
 * 요청 경로가 재건을 몇 번 띄우는지를 본다.
 *
 * 이벤트가 열리는 순간에는 수만 개가 같은 응답을 받으므로, 그 수만큼 재건이 뜨면 그것이 곧
 * 장애가 된다. 이 클래스가 지켜야 하는 성질은 "몰려도 하나" 와 "실패하면 다음이 다시 뜬다" 둘이다.
 */
@ExtendWith(MockitoExtension.class)
class CouponSeqRebuildTriggerTest {

    private static final long COUPON_ID = 9001L;

    @Mock
    private CouponSeqRebuilder rebuilder;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    void 한_번_부르면_재건이_한_번_뜬다() {
        CouponSeqRebuildTrigger sut = new CouponSeqRebuildTrigger(rebuilder, redisTemplate);

        sut.suspect(COUPON_ID);

        verify(rebuilder, timeout(5_000)).rebuildIfLost(COUPON_ID);
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
        }).when(rebuilder).rebuildIfLost(anyLong());

        CouponSeqRebuildTrigger sut = new CouponSeqRebuildTrigger(rebuilder, redisTemplate);

        // when 첫 요청이 재건을 띄우고, 그것이 도는 동안 이천 개가 더 들어온다
        sut.suspect(COUPON_ID);
        assertThat(재건이_시작됐다.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 2_000; i++) {
            sut.suspect(COUPON_ID);
        }
        재건을_붙잡는다.countDown();

        // then
        verify(rebuilder, timeout(5_000).times(1)).rebuildIfLost(COUPON_ID);
    }

    /*
     * 재건이 실패하면 표시가 지워져야 한다.
     * 안 지우면 그 쿠폰은 이 인스턴스가 살아 있는 동안 영영 재건되지 않는다.
     */
    @Test
    void 재건이_실패하면_다음_요청이_다시_띄운다() throws Exception {
        doThrow(new IllegalStateException("DB 가 답하지 않는다"))
                .when(rebuilder).rebuildIfLost(anyLong());
        CouponSeqRebuildTrigger sut = new CouponSeqRebuildTrigger(rebuilder, redisTemplate);

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
        verify(rebuilder, timeout(5_000).atLeast(2)).rebuildIfLost(COUPON_ID);
    }

    /*
     * 플러시 뒤 확인이 Redis 실패를 삼키는지 본다.
     *
     * 안 삼키면 플러시 루프의 바깥 catch 가 방금 성공한 배치를 실패로 처리한다. Redis 가 죽어도
     * 큐에 든 발급은 끝까지 간다는 성질(coupon.md 9장)이 이 한 줄에 걸려 있다.
     */
    @Test
    void 플러시_뒤_확인은_Redis_실패를_삼킨다() {
        when(redisTemplate.hasKey(anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis 가 답하지 않는다"));
        CouponSeqRebuildTrigger sut = new CouponSeqRebuildTrigger(rebuilder, redisTemplate);

        assertThatCode(() -> sut.checkAfterFlush(COUPON_ID)).doesNotThrowAnyException();

        verifyNoInteractions(rebuilder);
    }

    // 재건 중이면 이 인스턴스도 자기 큐를 올려야 한다
    @Test
    void 재건_표시가_있으면_후보로_넘긴다() {
        when(redisTemplate.hasKey("coupon:9001:rebuild")).thenReturn(true);
        CouponSeqRebuildTrigger sut = new CouponSeqRebuildTrigger(rebuilder, redisTemplate);

        sut.checkAfterFlush(COUPON_ID);

        verify(rebuilder, timeout(5_000)).rebuildIfLost(COUPON_ID);
    }

    // 평상시에는 아무 일도 안 한다. 배치마다 도는 자리라 여기서 새면 재건이 쉬지 않고 돈다
    @Test
    void 재건_표시가_없으면_아무것도_안_한다() {
        when(redisTemplate.hasKey("coupon:9001:rebuild")).thenReturn(false);
        CouponSeqRebuildTrigger sut = new CouponSeqRebuildTrigger(rebuilder, redisTemplate);

        sut.checkAfterFlush(COUPON_ID);

        verifyNoInteractions(rebuilder);
    }

    // 쿠폰이 다르면 서로를 막지 않는다. 집합의 키가 쿠폰이라는 뜻이다
    @Test
    void 다른_쿠폰은_서로를_막지_않는다() {
        CouponSeqRebuildTrigger sut = new CouponSeqRebuildTrigger(rebuilder, redisTemplate);

        sut.suspect(COUPON_ID);
        sut.suspect(COUPON_ID + 1);

        verify(rebuilder, timeout(5_000)).rebuildIfLost(COUPON_ID);
        verify(rebuilder, timeout(5_000)).rebuildIfLost(COUPON_ID + 1);
        verify(rebuilder, times(2)).rebuildIfLost(anyLong());
    }
}
