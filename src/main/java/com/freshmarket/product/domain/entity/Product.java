package com.freshmarket.product.domain.entity;

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

// 상품. 판매 단위는 옵션(ProductOption)이고 이 엔티티는 카탈로그 정보만 갖는다
@Entity
@Table(name = "product")
@AttributeOverride(name = "id", column = @Column(name = "product_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseMutableTimeEntity {

    private static final int PRODUCT_CODE_MAX_LENGTH = 50;
    private static final int NAME_MAX_LENGTH = 255;
    private static final int REQUEST_ID_MAX_LENGTH = 100;

    // 자동생성 상품코드. 소프트딜리트해도 이 값은 다시 쓰지 않는다
    @Column(name = "product_code", nullable = false, length = PRODUCT_CODE_MAX_LENGTH)
    private String productCode;

    // 재시도 감지용 요청 식별자(클라이언트 생성). 같은 값으로 다시 등록 요청이 오면 새로 만들지 않는다 (API-5-07, AIP-155)
    @Column(name = "request_id", nullable = false, length = REQUEST_ID_MAX_LENGTH)
    private String requestId;

    // 진열되는 상품명
    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    // 카테고리 FK. 연관관계 매핑 대신 식별자 컬럼으로 둔다 (JPA-1-02)
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    // 공급처 FK
    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    // 판매 상태. 신규 등록 시 항상 ON_SALE 로 시작하며 이후 상태 전이는 도메인 메서드로만 허용한다
    @Enumerated(EnumType.STRING)
    @Column(name = "sale_status", nullable = false, length = 30)
    private SaleStatus saleStatus;

    // 보관 온도. 배송/진열 방식을 가른다
    @Enumerated(EnumType.STRING)
    @Column(name = "storage_type", nullable = false, length = 30)
    private StorageType storageType;

    // 판매 가능 최소 잔여일수. 소비기한까지 이 일수 이상 남아야 판매한다
    @Column(name = "sale_available_days_from_expiry", nullable = false)
    private int saleAvailableDaysFromExpiry;

	// 상세 페이지에 노출되는 상품 설명. 선택값이라 없어도 등록 가능하다
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // 소프트딜리트. null 이 아니면 삭제된 상품이다
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 검증을 모으는 유일한 생성 경로. 모든 팩터리가 여기로 들어온다
    private Product(String requestId, String productCode, String name, Long categoryId, Long supplierId,
                    StorageType storageType, int saleAvailableDaysFromExpiry, String description) {
        validateRequestId(requestId);
        validateProductCode(productCode);
        validateName(name);
        validateCategoryId(categoryId);
        validateSupplierId(supplierId);
        validateStorageType(storageType);
        validateSaleAvailableDaysFromExpiry(saleAvailableDaysFromExpiry);
        this.requestId = requestId;
        this.productCode = productCode;
        this.name = name;
        this.categoryId = categoryId;
        this.supplierId = supplierId;
        this.storageType = storageType;
        this.saleAvailableDaysFromExpiry = saleAvailableDaysFromExpiry;
        this.description = description;
        this.saleStatus = SaleStatus.ON_SALE;
    }

    // 설명 없이 상품을 등록한다
    public static Product register(String requestId, String productCode, String name, Long categoryId,
                                   Long supplierId, StorageType storageType,
                                   int saleAvailableDaysFromExpiry) {
        return new Product(requestId, productCode, name, categoryId, supplierId,
                storageType, saleAvailableDaysFromExpiry, null);
    }

    // 설명을 함께 붙여 상품을 등록한다
    public static Product register(String requestId, String productCode, String name, Long categoryId,
                                   Long supplierId, StorageType storageType,
                                   int saleAvailableDaysFromExpiry, String description) {
        return new Product(requestId, productCode, name, categoryId, supplierId,
                storageType, saleAvailableDaysFromExpiry, description);
    }

    // 삭제된 상품인지 본다. 조회에서 목록 노출 여부를 가른다
    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    // 요청 식별자가 비어있지 않고 길이 제한을 넘지 않는지 검사한다
    private static void validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 는 필수다");
        }
        if (requestId.length() > REQUEST_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "requestId 는 " + REQUEST_ID_MAX_LENGTH + "자를 넘을 수 없다: " + requestId.length());
        }
    }

    // 상품코드가 비어있지 않고 길이 제한을 넘지 않는지 검사한다
    private static void validateProductCode(String productCode) {
        if (productCode == null || productCode.isBlank()) {
            throw new IllegalArgumentException("productCode 는 필수다");
        }
        if (productCode.length() > PRODUCT_CODE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "productCode 는 " + PRODUCT_CODE_MAX_LENGTH + "자를 넘을 수 없다: " + productCode.length());
        }
    }

    // 상품명이 비어있지 않고 길이 제한을 넘지 않는지 검사한다
    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 은 필수다");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "name 은 " + NAME_MAX_LENGTH + "자를 넘을 수 없다: " + name.length());
        }
    }

    // 카테고리 FK 가 지정되었는지 검사한다. 실재 여부는 서비스가 확인한다
    private static void validateCategoryId(Long categoryId) {
        if (categoryId == null) {
            throw new IllegalArgumentException("categoryId 는 필수다");
        }
    }

    // 공급처 FK 가 지정되었는지 검사한다
    private static void validateSupplierId(Long supplierId) {
        if (supplierId == null) {
            throw new IllegalArgumentException("supplierId 는 필수다");
        }
    }

    // 보관 온도가 지정되었는지 검사한다
    private static void validateStorageType(StorageType storageType) {
        if (storageType == null) {
            throw new IllegalArgumentException("storageType 은 필수다");
        }
    }

    // 판매 가능 최소 잔여일수가 음수가 아닌지 검사한다
    private static void validateSaleAvailableDaysFromExpiry(int days) {
        if (days < 0) {
            throw new IllegalArgumentException("saleAvailableDaysFromExpiry 는 0 이상이어야 한다: " + days);
        }
    }
}