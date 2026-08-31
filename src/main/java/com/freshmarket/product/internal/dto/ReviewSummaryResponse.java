package com.freshmarket.product.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// 상품 상세에 딸려 나가는 리뷰 요약. review 도메인 신설 여부가 미정이라 지금은 항상 0/null 을 준다
public record ReviewSummaryResponse(
        @Schema(description = "리뷰 건수. review 도메인 도입 전이라 현재는 항상 0이다", example = "0")
        long count,
        @Schema(description = "평균 평점. review 도메인 도입 전이라 현재는 항상 null이다", example = "null")
        Double averageRating
) {
}