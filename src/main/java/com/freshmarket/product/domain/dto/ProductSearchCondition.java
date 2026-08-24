package com.freshmarket.product.domain.dto;

import com.freshmarket.common.response.PageCursor;

import io.swagger.v3.oas.annotations.media.Schema;

// 상품 목록/검색 조회 조건. 컨트롤러가 요청 파라미터를 조립해 서비스로 넘긴다.
// Swagger 요청/응답 스펙에는 직접 나타나지 않지만, 문서 규칙(API-7-02)에 맞춰 명시한다.
public record ProductSearchCondition(
        @Schema(description = "카테고리 ID 필터", example = "4") Long categoryId,
        @Schema(description = "최소 가격 필터(원)", example = "5000") Integer minPriceKrw,
        @Schema(description = "최대 가격 필터(원)", example = "50000") Integer maxPriceKrw,
        @Schema(description = "검색어. 상품명 부분 일치. 검색 엔드포인트에서만 채워진다",
                example = "감귤") String query,
        @Schema(description = "정렬 기준") ProductSortType sort,
        @Schema(description = "다음 페이지 커서. 첫 페이지는 null") PageCursor cursor,
        @Schema(description = "페이지 크기. 최대 100", example = "20") int pageSize
) {

    private static final ProductSortType DEFAULT_SORT = ProductSortType.CREATED_DESC;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_QUERY_LENGTH = 100;

    public ProductSearchCondition {
        if (sort == null) {
            sort = DEFAULT_SORT;
        }
        if (pageSize <= 0) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }
        if (minPriceKrw != null && minPriceKrw < 0) {
            throw new IllegalArgumentException("minPriceKrw 는 0 이상이어야 한다: " + minPriceKrw);
        }
        if (maxPriceKrw != null && maxPriceKrw < 0) {
            throw new IllegalArgumentException("maxPriceKrw 는 0 이상이어야 한다: " + maxPriceKrw);
        }
        if (minPriceKrw != null && maxPriceKrw != null && minPriceKrw > maxPriceKrw) {
            throw new IllegalArgumentException(
                    "minPriceKrw 가 maxPriceKrw 보다 클 수 없다: " + minPriceKrw + " > " + maxPriceKrw);
        }
        if (query != null) {
            query = query.strip();
            if (query.isBlank()) {
                throw new IllegalArgumentException("query 는 공백일 수 없다");
            }
            if (query.length() > MAX_QUERY_LENGTH) {
                throw new IllegalArgumentException(
                        "query 는 " + MAX_QUERY_LENGTH + "자를 넘을 수 없다: " + query.length());
            }
        }
    }
}