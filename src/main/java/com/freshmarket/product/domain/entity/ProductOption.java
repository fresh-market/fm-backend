package com.freshmarket.product.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 상품 옵션. 판매 단위(SKU)이며 가격과 재고는 여기 기준이다
@Entity
@Table(name = "product_option")
@AttributeOverride(name = "id", column = @Column(name = "product_option_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOption extends BaseMutableTimeEntity {

    private static final int NAME_MAX_LENGTH = 100;

    // 상품 FK
    @Column(name = "product_id", nullable = false)
    private Long productId;

    // 옵션명(예: 200g, 500g, 1kg)
    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(name = "price", nullable = false)
    private int price;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_status", nullable = false, length = 30)
    private SaleStatus saleStatus;

    private ProductOption(Long productId, String name, int price) {
        validateProductId(productId);
        validateName(name);
        validatePrice(price);
        this.productId = productId;
        this.name = name;
        this.price = price;
        this.saleStatus = SaleStatus.ON_SALE;
    }

    // 상품에 새 옵션을 추가한다
    public static ProductOption register(Long productId, String name, int price) {
        return new ProductOption(productId, name, price);
    }

    private static void validateProductId(Long productId) {
        if (productId == null) {
            throw new IllegalArgumentException("productId 는 필수다");
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 은 필수다");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "name 은 " + NAME_MAX_LENGTH + "자를 넘을 수 없다: " + name.length());
        }
    }

    private static void validatePrice(int price) {
        if (price < 0) {
            throw new IllegalArgumentException("price 는 0 이상이어야 한다: " + price);
        }
    }
}