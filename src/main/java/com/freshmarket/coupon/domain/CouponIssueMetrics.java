package com.freshmarket.coupon.domain;

import com.freshmarket.coupon.domain.cache.CouponCache;
import com.freshmarket.coupon.domain.issue.CouponIssueQueue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.stereotype.Component;

/*
 * 8장이 재라고 한 것 중 앱이 직접 내야 하는 값을 등록한다.
 *
 * 나머지는 이미 나오고 있다. 처리량과 p99 는 http.server.requests 가, DB 커넥션 대기는
 * hikaricp_connections_pending 이 낸다. 여기 둘은 그 자동 계측이 모르는 값이다.
 */
@Component
public class CouponIssueMetrics {

    public CouponIssueMetrics(MeterRegistry registry, CouponIssueQueue queue, CouponCache couponCache) {
        registerQueueSize(registry, queue);
        registerCacheStats(registry, couponCache);
    }

    /*
     * 앱은 현재 길이만 낸다. 8장이 요구하는 "큐 최대 길이" 는 대시보드가 max_over_time 으로 뽑는다.
     * 앱이 최댓값을 들고 있으면 언제 리셋할지를 앱이 정해야 하고, 그 창이 대시보드의 창과 안 맞는다.
     *
     * 이 값이 곧 앱이 급사했을 때 잃는 건수의 상한이다. p99 와 함께 봐야 하는 이유가 있다.
     * 가상 스레드는 요청을 다 받아들이므로 폭주가 실패가 아니라 지연으로 나타나는데,
     * 큐 길이를 같이 보면 얼마나 밀려 있는지가 드러난다.
     */
    private static void registerQueueSize(MeterRegistry registry, CouponIssueQueue queue) {
        Gauge.builder("coupon.issue.queue.size", queue, CouponIssueQueue::size)
                .description("아직 플러시되지 않은 발급 건수. 앱이 급사하면 이만큼 잃는다")
                .register(registry);
    }

    /*
     * 적중률이 coupon.issue.coupon-cache-ttl 을 조정할 때의 근거가 된다.
     * TTL 을 줄이면 마감 초과 허용치가 줄어드는 대신 DB 조회가 는다. 그 교환을 숫자로 봐야 한다.
     */
    private static void registerCacheStats(MeterRegistry registry, CouponCache couponCache) {
        CaffeineCacheMetrics.monitor(registry, couponCache.forMetrics(), "coupon.eligibility");
    }
}
