package com.freshmarket.product.internal.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
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

    // 재고 기반 품절 여부. sale_status와는 별개다
    @Column(name = "sold_out", nullable = false)
    private boolean soldOut;

    /*
     * (DI-2-01) sold_out을 마지막으로 갱신한 이벤트의 발생 시각. ProductOptionRepository의 조건부
     * UPDATE가 "더 최신 이벤트만 반영"을 판정하는 기준이라, 이 값을 직접 대입하는 도메인 메서드를
     * 두지 않는다 — 그 UPDATE 자체가 sold_out과 이 값을 원자적으로 함께 갱신한다.
     */
    @Column(name = "sold_out_synced_at")
    private LocalDateTime soldOutSyncedAt;

    private ProductOption(Long productId, String name, int price) {
        validateProductId(productId);
        validateName(name);
        validatePrice(price);
        this.productId = productId;
        this.name = name;
        this.price = price;
        this.saleStatus = SaleStatus.ON_SALE;
        this.soldOut = true;
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