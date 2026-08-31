package com.freshmarket.product.internal.dto;

// 상품 목록 정렬 기준. 허용값 화이트리스트 역할을 겸한다
public enum ProductSortType {

    // 판매순. daily_sales 가 필요해 statistics 도메인 도입 후 구현한다
    SALES_DESC,

    // 최신 등록순
    CREATED_DESC,

    // 옵션 최저가 오름차순
    PRICE_ASC,

    // 옵션 최저가 내림차순
    PRICE_DESC
}