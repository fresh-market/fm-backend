package com.freshmarket.product.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

// ProductOption 의 등록 검증을 확인한다
class ProductOptionTest {

    @Test
    void 필수값이_모두_있으면_옵션을_등록한다() {
        // when
        ProductOption option = ProductOption.register(1L, "1kg", 12900);

        // then
        assertThat(option.getProductId()).isEqualTo(1L);
        assertThat(option.getName()).isEqualTo("1kg");
        assertThat(option.getPrice()).isEqualTo(12900);
        assertThat(option.getSaleStatus()).isEqualTo(SaleStatus.ON_SALE);
    }

    @Test
    void 상품_id가_없으면_등록에_실패한다() {
        assertThatThrownBy(() -> ProductOption.register(null, "1kg", 12900))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productId");
    }

    @Test
    void 옵션명이_비어있으면_등록에_실패한다() {
        assertThatThrownBy(() -> ProductOption.register(1L, "", 12900))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void 옵션명이_100자를_넘으면_등록에_실패한다() {
        String tooLong = "옵".repeat(101);
        assertThatThrownBy(() -> ProductOption.register(1L, tooLong, 12900))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void 가격이_음수면_등록에_실패한다() {
        assertThatThrownBy(() -> ProductOption.register(1L, "1kg", -100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price");
    }
}