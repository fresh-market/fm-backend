package com.freshmarket.product.domain.entity;

import java.time.LocalDateTime;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * 테스트에서 반복되는 엔티티 생성을 모은다.
 * id 는 register() 로 못 받으니(AUTO_INCREMENT), 필요할 때만 리플렉션으로 채운다.
 */
public final class ProductFixture {

    private ProductFixture() {
    }

    // id 가 채워진 상품. 서비스 단위 테스트에서 리포지토리가 반환하는 상황을 흉내낼 때 쓴다
    public static Product 상품(Long id, String name, Long categoryId) {
        Product product = Product.register(
                "req-" + id, "P-" + name, name, categoryId, 1L, StorageType.COLD, 3);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    // 삭제된 상품
    public static Product 삭제된_상품(Long id, String name, Long categoryId) {
        Product product = 상품(id, name, categoryId);
        ReflectionTestUtils.setField(product, "deletedAt", LocalDateTime.now());
        return product;
    }

    public static Category 카테고리(Long id, String name) {
        Category category = Category.register(name);
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }

    public static ProductOption 옵션(Long productId, String name, int price) {
        return ProductOption.register(productId, name, price);
    }
}