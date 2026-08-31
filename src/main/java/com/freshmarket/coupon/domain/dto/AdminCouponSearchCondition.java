package com.freshmarket.coupon.domain.dto;

import com.freshmarket.common.response.PageCursor;
import com.freshmarket.coupon.domain.entity.CouponScope;
import io.swagger.v3.oas.annotations.media.Schema;

// 관리자 쿠폰 목록 조회 조건. 컨트롤러가 요청 파라미터를 조립해 서비스로 넘긴다
public record AdminCouponSearchCondition(
        @Schema(description = "활성 여부 필터. null이면 전체", example = "true") Boolean isActive,
        @Schema(description = "적용 범위 필터. null이면 전체") CouponScope scope,
        @Schema(description = "다음 페이지 커서. 첫 페이지는 null") PageCursor cursor,
        @Schema(description = "페이지 크기. 최대 100", example = "20") int pageSize
) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    public AdminCouponSearchCondition {
        if (pageSize <= 0) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }
    }
}
