package com.freshmarket.product.domain.dto;

import com.freshmarket.product.domain.entity.SaleStatus;
import io.swagger.v3.oas.annotations.media.Schema;

// 관리자 상품 목록 조회 조건. 컨트롤러가 요청 파라미터를 조립해 서비스로 넘긴다.
public record AdminProductSearchCondition(
        @Schema(description = "검색어. 상품명 부분 일치", example = "감귤") String query,
        @Schema(description = "카테고리 ID 필터", example = "4") Long categoryId,
        @Schema(description = "판매 상태 필터") SaleStatus saleStatus,
        @Schema(description = "삭제된 상품도 포함할지 여부", example = "false") boolean includeDeleted,
        @Schema(description = "페이지 번호(0부터 시작)", example = "0") int page,
        @Schema(description = "페이지 크기. 최대 100", example = "20") int size
) {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final int MAX_QUERY_LENGTH = 100;

    public AdminProductSearchCondition {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = DEFAULT_SIZE;
        }
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
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
