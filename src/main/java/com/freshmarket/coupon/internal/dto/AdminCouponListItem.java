package com.freshmarket.coupon.internal.dto;

import com.freshmarket.coupon.internal.entity.CouponScope;
import com.freshmarket.coupon.internal.entity.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;

// 관리자 쿠폰 목록의 항목 하나
public record AdminCouponListItem(
        @Schema(description = "쿠폰 ID", example = "1") Long couponId,
        @Schema(description = "쿠폰명", example = "소비기한 임박 30% 할인") String name,
        @Schema(description = "적용 범위") CouponScope scope,
        @Schema(description = "할인 유형") DiscountType discountType,
        @Schema(description = "할인 값. 정액은 원, 정률은 %", example = "30") int discountValue,
        @Schema(description = "정률 할인 상한. 정액이면 null") Integer maxDiscountAmount,
        @Schema(description = "최소 주문 금액", example = "20000") int minOrderAmount,
        @Schema(description = "발급 한정 수량. null이면 무제한") Integer totalQuantity,
        @Schema(description = "지금까지 나간 발급 수", example = "8231") int issuedQuantity,
        @Schema(description = "발급 시작 시각. null이면 제한 없음") LocalDateTime issueStartAt,
        @Schema(description = "발급 마감 시각") LocalDateTime issueEndAt,
        @Schema(description = "사용 유효 시작일") LocalDate validFrom,
        @Schema(description = "사용 유효 종료일") LocalDate validTo,
        @Schema(description = "발급 대상 등급 ID. null이면 전체 회원") Long targetGradeId,
        @Schema(description = "활성 여부", example = "true") boolean isActive
) {
}
