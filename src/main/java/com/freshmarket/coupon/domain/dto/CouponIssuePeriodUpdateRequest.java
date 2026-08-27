package com.freshmarket.coupon.domain.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;

/**
 * 관리자가 발급 시각을 바꿀 때 보내는 값이다.
 *
 * <p>두 시각을 함께 받는다. 하나만 받으면 관리자가 끝 시각만 미뤄 사용자가 본 마감을 뒤로
 * 밀 수 있고, 그것도 약속을 흔드는 것이다. 앞뒤 순서는 {@code chk_coupon_issue_period} 가 막는다.
 */
public record CouponIssuePeriodUpdateRequest(
        @NotNull LocalDateTime issueStartAt,
        @NotNull LocalDateTime issueEndAt) {
}
