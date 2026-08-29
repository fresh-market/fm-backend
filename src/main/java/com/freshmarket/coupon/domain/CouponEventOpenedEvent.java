package com.freshmarket.coupon.domain;

// 관리자가 선착순 이벤트를 열고 그 트랜잭션이 커밋됐다는 사실이다.
public record CouponEventOpenedEvent(long couponId) {
}
