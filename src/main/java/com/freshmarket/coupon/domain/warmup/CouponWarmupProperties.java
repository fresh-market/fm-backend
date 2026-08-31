package com.freshmarket.coupon.domain.warmup;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 기동 직후 발급 경로를 데우는 값들이다.
 *
 * @param enabled       꺼 두면 러너가 아무것도 하지 않는다. 로컬과 시험에서 쓴다
 * @param couponId      워밍업 전용 쿠폰. 러너가 카운터를 소진 상태로 세워 두어 DB 에 아무것도 안 쓴다
 * @param requests      보낼 요청 수. 3,000 에서 p99 가 4.69초에서 1초 아래로 떨어졌다
 * @param concurrency   동시에 보낼 수. 순차로 보내면 실제 이벤트의 동시성을 못 흉내 낸다
 * @param maxDuration   이 시간을 넘기면 못 채워도 끝낸다. 없으면 readiness 가 영영 안 올라간다
 */
@ConfigurationProperties("coupon.warmup")
public record CouponWarmupProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1000000") long couponId,
        @DefaultValue("3000") int requests,
        @DefaultValue("20") int concurrency,
        @DefaultValue("60s") Duration maxDuration) {

    public CouponWarmupProperties {
        require(requests > 0, "requests 는 1 이상이어야 한다");
        require(concurrency > 0, "concurrency 는 1 이상이어야 한다");
        require(!maxDuration.isNegative() && !maxDuration.isZero(), "maxDuration 은 0 보다 커야 한다");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
