package com.freshmarket.coupon.internal.warmup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/*
 * 워밍업 값이 제대로 묶이는지, 그리고 위험한 값을 기동에서 막는지 본다.
 */
class CouponWarmupPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(CouponWarmupConfig.class);

    /*
     * 상한이 없으면 워밍업이 안 끝나 readiness 가 영영 안 올라가고 ASG 가 인스턴스를
     * 교체하는 루프에 빠진다. 그래서 0 을 기동에서 막는다.
     */
    @Test
    void 상한이_0_이면_기동에서_막는다() {
        runner.withPropertyValues("coupon.warmup.max-duration=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 요청_수가_0_이면_기동에서_막는다() {
        runner.withPropertyValues("coupon.warmup.requests=0")
                .run(context -> assertThat(context).hasFailed());
    }

    // 적지 않으면 꺼진 채로 뜬다. 쿠폰 프로필만 켠다
    @Test
    void 기본은_꺼져_있다() {
        runner.run(context ->
                assertThat(context.getBean(CouponWarmupProperties.class).enabled()).isFalse());
    }

    /*
     * 값을 문자열로 그대로 적는다. 3,000 은 실측에서 나온 값이라
     * (200 으로는 최대 응답 1.72초, 3,000 에서 p99 1초 아래) 바뀌면 근거가 사라진다.
     */
    @Test
    void 쿠폰_프로필의_값이_그대로_묶인다() {
        runner.withPropertyValues(
                        "coupon.warmup.enabled=true",
                        "coupon.warmup.coupon-id=1000000",
                        "coupon.warmup.requests=3000",
                        "coupon.warmup.concurrency=20",
                        "coupon.warmup.max-duration=60s")
                .run(context -> {
                    CouponWarmupProperties p = context.getBean(CouponWarmupProperties.class);
                    assertThat(p.enabled()).isTrue();
                    assertThat(p.couponId()).isEqualTo(1000000L);
                    assertThat(p.requests()).isEqualTo(3000);
                    assertThat(p.concurrency()).isEqualTo(20);
                    assertThat(p.maxDuration()).isEqualTo(Duration.ofSeconds(60));
                });
    }

    @Test
    void 음수_동시성은_막는다() {
        assertThatThrownBy(() -> new CouponWarmupProperties(true, 1L, 10, 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("concurrency");
    }
}
