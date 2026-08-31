package com.freshmarket.coupon.domain.dto;

import com.freshmarket.coupon.domain.entity.CouponScope;
import com.freshmarket.coupon.domain.entity.DiscountType;
import com.querydsl.core.annotations.QueryProjection;
import java.time.LocalDate;
import java.time.LocalDateTime;

// 관리자 쿠폰 목록 쿼리 전용 프로젝션. createdAt은 응답에는 안 실리고 다음 페이지 토큰 계산에만 쓴다
public record AdminCouponListRow(
        Long couponId,
        String name,
        CouponScope scope,
        DiscountType discountType,
        int discountValue,
        Integer maxDiscountAmount,
        int minOrderAmount,
        Integer totalQuantity,
        int issuedQuantity,
        LocalDateTime issueStartAt,
        LocalDateTime issueEndAt,
        LocalDate validFrom,
        LocalDate validTo,
        Long targetGradeId,
        boolean active,
        LocalDateTime createdAt
) {

    @QueryProjection
    public AdminCouponListRow {
        // QueryDSL이 @QueryProjection 생성자를 찾을 수 있게 record의 canonical 생성자에 어노테이션만 붙인다
    }
}
