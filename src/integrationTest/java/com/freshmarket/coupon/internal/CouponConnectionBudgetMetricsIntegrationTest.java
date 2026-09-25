package com.freshmarket.coupon.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.freshmarket.IntegrationTestSupport;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/*
 * 커넥션 풀 크기를 밖에서 읽을 수 있는지 본다.
 *
 * fm-infra 의 coupon-event.sh 가 이 지표에 기댄다. 전에는 그 스크립트가 세 프로필의
 * maximum-pool-size 를 상수로 베껴 들고 있었는데, 이쪽이 바뀌면 저쪽이 조용히 틀렸다.
 * 지금은 SSM 으로 인스턴스 안에서 /actuator/prometheus 를 긁어 실측한다.
 *
 * 그래서 이 이름과 이 노출이 계약이 됐다. 둘 중 하나가 사라지면 앱은 멀쩡히 뜨고 스크립트만
 * 측정을 잃는데, 그것을 이벤트 직전에 알게 된다. 여기서 막는다.
 *
 * 이 테스트가 쿠폰 아래 사는 것은 이 계약을 쓰는 곳이 선착순 이벤트 절차여서다.
 */
@SpringBootTest
class CouponConnectionBudgetMetricsIntegrationTest extends IntegrationTestSupport {

    // coupon-event.sh 의 pool_max 가 grep 하는 이름이다. Prometheus 형식으로는 밑줄이 된다
    private static final String POOL_MAX = "hikaricp.connections.max";

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private Environment environment;

    @Test
    void 풀_최대_크기가_지표로_나온다() {
        Gauge gauge = registry.find(POOL_MAX).gauge();

        assertThat(gauge)
                .as("%s 가 없다. coupon-event.sh 가 풀 크기를 못 읽는다", POOL_MAX)
                .isNotNull();
        assertThat(gauge.value())
                .as("풀 크기가 0 이면 스크립트의 검산이 0 으로 나눈다")
                .isPositive();
    }

    /*
     * 지표가 등록돼도 밖으로 안 나가면 스크립트는 못 읽는다.
     * 노출 목록에서 prometheus 가 빠지는 것이 가장 흔한 사고다.
     */
    @Test
    void prometheus_엔드포인트가_노출된다() {
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .as("management.endpoints.web.exposure.include 에서 prometheus 가 빠졌다")
                .contains("prometheus");
    }
}
