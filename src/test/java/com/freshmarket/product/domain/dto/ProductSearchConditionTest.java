package com.freshmarket.product.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

// ProductSearchCondition 의 기본값 적용과 검증을 확인한다
class ProductSearchConditionTest {

    @Test
    void 정렬을_주지_않으면_기본_정렬을_쓴다() {
        ProductSearchCondition condition =
                new ProductSearchCondition(null, null, null, null, null, null, 20);

        assertThat(condition.sort()).isEqualTo(ProductSortType.CREATED_DESC);
    }

    @Test
    void 페이지크기가_0_이하면_기본값을_쓴다() {
        ProductSearchCondition condition =
                new ProductSearchCondition(null, null, null, null, null, null, 0);

        assertThat(condition.pageSize()).isEqualTo(20);
    }

    @Test
    void 페이지크기가_상한을_넘으면_상한으로_잘린다() {
        ProductSearchCondition condition =
                new ProductSearchCondition(null, null, null, null, null, null, 500);

        assertThat(condition.pageSize()).isEqualTo(100);
    }

    @Test
    void 최소가격이_음수면_생성에_실패한다() {
        assertThatThrownBy(() ->
                new ProductSearchCondition(null, -1, null, null, null, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minPrice");
    }

    @Test
    void 최대가격이_음수면_생성에_실패한다() {
        assertThatThrownBy(() ->
                new ProductSearchCondition(null, null, -1, null, null, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPrice");
    }

    @Test
    void 최소가격이_최대가격보다_크면_생성에_실패한다() {
        assertThatThrownBy(() ->
                new ProductSearchCondition(null, 50000, 10000, null, null, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minPrice");
    }

    @Test
    void 검색어가_없어도_생성된다() {
        ProductSearchCondition condition =
                new ProductSearchCondition(null, null, null, null, null, null, 20);

        assertThat(condition.query()).isNull();
    }

    @Test
    void 정상적인_검색어는_그대로_담긴다() {
        ProductSearchCondition condition =
                new ProductSearchCondition(null, null, null, "감귤", null, null, 20);

        assertThat(condition.query()).isEqualTo("감귤");
    }

    @Test
    void 검색어가_공백이면_생성에_실패한다() {
        assertThatThrownBy(() ->
                new ProductSearchCondition(null, null, null, "   ", null, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void 검색어가_100자를_넘으면_생성에_실패한다() {
        String tooLong = "감".repeat(101);
        assertThatThrownBy(() ->
                new ProductSearchCondition(null, null, null, tooLong, null, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }
}