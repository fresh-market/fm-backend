package com.freshmarket.coupon.domain;

import com.freshmarket.coupon.domain.cache.CouponCache;
import com.freshmarket.coupon.domain.issue.CouponIssueQueue;
import com.freshmarket.coupon.domain.issue.IssueResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/*
 * 이 클래스가 8장이 재라고 한 값 중에서 앱이 직접 내야 하는 것을 등록한다.
 *
 * 나머지는 스프링의 자동 계측이 이미 내고 있다. 처리량과 p99 는 http.server.requests 가,
 * DB 커넥션 대기는 hikaricp_connections_pending 이 낸다. 이 클래스가 내는 둘은 그 자동
 * 계측이 알 수 없는 값이다.
 */
@Component
public class CouponIssueMetrics {

    private static final String RESULTS = "coupon.issue.results";

    private final Map<IssueResult, Counter> countersByResult;

    public CouponIssueMetrics(MeterRegistry registry, CouponIssueQueue queue, CouponCache couponCache) {
        registerQueueSize(registry, queue);
        registerCacheStats(registry, couponCache);
        this.countersByResult = registerResults(registry);
    }

    /** 발급 한 건이 어떻게 끝났는지 센다. 서비스가 모든 갈래에서 부른다. */
    public void record(IssueResult result) {
        countersByResult.get(result).increment();
    }

    /*
     * 이 생성자가 갈래별 계량기를 기동 때 다 만들어 둔다.
     * 그 갈래가 처음 나올 때 만들면, 한 번도 안 난 갈래는 대시보드에 아예 안 보인다.
     * 그러면 "혼잡이 0 건" 과 "혼잡을 안 센다" 가 같은 모양이 되어 읽는 사람이 구분하지 못한다.
     */
    private static Map<IssueResult, Counter> registerResults(MeterRegistry registry) {
        Map<IssueResult, Counter> counters = new EnumMap<>(IssueResult.class);
        for (IssueResult result : IssueResult.values()) {
            counters.put(result, Counter.builder(RESULTS)
                    .tag("result", result.tag())
                    .description("발급 결과. 8장이 요구한 대로 충돌과 소진과 혼잡과 DB 실패를 나눠 센다")
                    .register(registry));
        }
        return counters;
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
     * 이 적중률이 coupon.issue.coupon-cache-ttl 을 조정할 때의 근거가 된다.
     * TTL 을 줄이면 마감 초과 허용치가 줄어드는 대신 DB 조회가 는다. 그 교환을 숫자로 봐야 한다.
     */
    private static void registerCacheStats(MeterRegistry registry, CouponCache couponCache) {
        CaffeineCacheMetrics.monitor(registry, couponCache.forMetrics(), "coupon.eligibility");
    }
}
