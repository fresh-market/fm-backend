package com.freshmarket.coupon.domain.repository;

import com.freshmarket.coupon.domain.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

// 발급 경로는 쿠폰 한 건을 PK 로 읽는 것이 전부라 기본 메서드로 족하다
public interface CouponRepository extends JpaRepository<Coupon, Long> {
}
