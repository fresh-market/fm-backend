package com.freshmarket.coupon.domain.issue;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/*
 * 조율 값을 빈으로 올린다.
 * com.freshmarket.config 아래에 두지 않는 이유는 그쪽이 도메인 타입을 참조할 수 없어서다.
 */
@Configuration
@EnableConfigurationProperties(CouponIssueProperties.class)
public class CouponIssueConfig {
}
