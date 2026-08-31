package com.freshmarket.coupon.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.coupon.internal.cache.CouponCache;
import com.freshmarket.coupon.internal.issue.IssueResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

/*
 * 지표가 실제 레지스트리에 붙는지 본다.
 *
 * 이름이나 등록이 틀리면 앱은 멀쩡히 뜨고 대시보드만 비어 있다. 그것을 부하 시험 당일에
 * 알게 되면 그 회차를 통째로 다시 돌려야 한다.
 */
@SpringBootTest
@Sql("/sql/coupon-issue-fixture.sql")
class CouponIssueMetricsIntegrationTest extends IntegrationTestSupport {

    private static final long COUPON_ID = 9001L;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private CouponCache couponCache;

    /*
     * 갈래마다 계량기가 기동 때 다 서 있어야 한다.
     * 처음 그 갈래가 나올 때 만들면 "혼잡이 0 건" 과 "혼잡을 안 센다" 가 대시보드에서 같아 보인다.
     */
    @Test
    void 모든_결과_갈래가_기동_때_등록된다() {
        for (IssueResult result : IssueResult.values()) {
            assertThat(registry.find("coupon.issue.results").tag("result", result.tag()).counter())
                    .as("%s 계량기가 없다", result.tag())
                    .isNotNull();
        }
    }

    // 8장이 나눠 세라고 한 넷이 서로 다른 값으로 잡히는지 본다
    @Test
    void 충돌과_소진과_DB_실패가_다른_값으로_잡힌다() {
        assertThat(IssueResult.SEQ_TAKEN.tag())
                .isNotEqualTo(IssueResult.DB_FAILED.tag())
                .isNotEqualTo(IssueResult.SOLD_OUT.tag())
                .isNotEqualTo(IssueResult.QUEUE_FULL.tag());
    }

    // 8장이 "큐 최대 길이" 로 요구한 값이다. 앱은 현재 길이를 내고 최댓값은 대시보드가 뽑는다
    @Test
    void 큐_길이가_지표로_나온다() {
        assertThat(registry.find("coupon.issue.queue.size").gauge()).isNotNull();
        assertThat(registry.get("coupon.issue.queue.size").gauge().value()).isZero();
    }

    /*
     * 적중률이 coupon-cache-ttl 을 조정할 때의 근거다.
     * 캐시를 실제로 태워야 계량기가 값을 갖기 시작하므로 두 번 찾아 본다.
     */
    @Test
    void 캐시_적중과_실패가_지표로_나온다() {
        couponCache.evict(COUPON_ID);

        couponCache.find(COUPON_ID);
        couponCache.find(COUPON_ID);

        double 조회수 = registry.get("cache.gets").tag("cache", "coupon.eligibility")
                .functionCounters().stream().mapToDouble(counter -> counter.count()).sum();
        assertThat(조회수).isPositive();
    }
}
