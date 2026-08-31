package com.freshmarket.coupon.internal.warmup;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/*
 * 워밍업 값을 빈으로 올린다.
 * CouponIssueConfig 와 같은 이유로 도메인 아래에 둔다. com.freshmarket.config 는 도메인 타입을
 * 참조할 수 없다.
 */
@Configuration
@EnableConfigurationProperties(CouponWarmupProperties.class)
public class CouponWarmupConfig {
}
