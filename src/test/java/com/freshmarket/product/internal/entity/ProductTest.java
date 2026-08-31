package com.freshmarket.product.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

// Product 의 등록 검증과 삭제 여부 판정을 검증한다
class ProductTest {

    @Test
    void 필수값이_모두_있으면_상품을_등록한다() {
        // when
        Product product = Product.register(
                "req-1", "P-001", "감귤", 4L, 1L, StorageType.COLD, 3);

        // then
        assertThat(product.getRequestId()).isEqualTo("req-1");
        assertThat(product.getProductCode()).isEqualTo("P-001");
        assertThat(product.getName()).isEqualTo("감귤");
        assertThat(product.getSaleStatus()).isEqualTo(SaleStatus.ON_SALE);
        assertThat(product.isDeleted()).isFalse();
    }

    @Test
    void 설명을_포함해_상품을_등록한다() {
        // when
        Product product = Product.register(
                "req-1", "P-001", "감귤", 4L, 1L, StorageType.COLD, 3, "제주산");

        // then
        assertThat(product.getDescription()).isEqualTo("제주산");
    }

    @Test
    void 요청식별자가_비어있으면_등록에_실패한다() {
        assertThatThrownBy(() ->
                Product.register("", "P-001", "감귤", 4L, 1L, StorageType.COLD, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestId");
    }

    @Test
    void 요청식별자가_100자를_넘으면_등록에_실패한다() {
        String tooLong = "r".repeat(101);
        assertThatThrownBy(() ->
                Product.register(tooLong, "P-001", "감귤", 4L, 1L, StorageType.COLD, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestId");
    }

    @Test
    void 상품코드가_비어있으면_등록에_실패한다() {
        assertThatThrownBy(() ->
                Product.register("req-1", "", "감귤", 4L, 1L, StorageType.COLD, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productCode");
    }

    @Test
    void 상품코드가_50자를_넘으면_등록에_실패한다() {
        String tooLong = "P".repeat(51);
        assertThatThrownBy(() ->
                Product.register("req-1", tooLong, "감귤", 4L, 1L, StorageType.COLD, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productCode");
    }

    @Test
    void 상품명이_비어있으면_등록에_실패한다() {
        assertThatThrownBy(() ->
                Product.register("req-1", "P-001", "", 4L, 1L, StorageType.COLD, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void 상품명이_255자를_넘으면_등록에_실패한다() {
        String tooLong = "감".repeat(256);
        assertThatThrownBy(() ->
                Product.register("req-1", "P-001", tooLong, 4L, 1L, StorageType.COLD, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void 카테고리가_없으면_등록에_실패한다() {
        assertThatThrownBy(() ->
                Product.register("req-1", "P-001", "감귤", null, 1L, StorageType.COLD, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("categoryId");
    }

    @Test
    void 공급처가_없으면_등록에_실패한다() {
        assertThatThrownBy(() ->
                Product.register("req-1", "P-001", "감귤", 4L, null, StorageType.COLD, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supplierId");
    }

    @Test
    void 보관온도가_없으면_등록에_실패한다() {
        assertThatThrownBy(() ->
                Product.register("req-1", "P-001", "감귤", 4L, 1L, null, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storageType");
    }

    @Test
    void 판매가능잔여일수가_음수면_등록에_실패한다() {
        assertThatThrownBy(() ->
                Product.register("req-1", "P-001", "감귤", 4L, 1L, StorageType.COLD, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("saleAvailableDaysFromExpiry");
    }
}
