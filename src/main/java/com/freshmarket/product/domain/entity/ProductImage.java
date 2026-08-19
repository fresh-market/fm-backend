package com.freshmarket.product.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

// 상품 이미지. S3에 올라간 객체 하나를 가리키며, CONFIRMED 상태만 조회에 노출된다
@Entity
@Table(name = "product_image")
@AttributeOverride(name = "id", column = @Column(name = "product_image_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage extends BaseMutableTimeEntity {

    private static final int OBJECT_KEY_MAX_LENGTH = 255;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /*
     * 업로드 세션 식별자(스키마 명세상 UUID v7, INF-11-04). product_image_id(리소스 식별자)와 달리,
     * 완료 통지 요청이 이 업로드를 발급받은 본인인지 확인하는 용도다.
     * ORM이 INSERT 직전에 채운다(identifier-strategy-guideline.md 5절과 같은 생성 시점 원칙).
     * public_id 전용 베이스 클래스(BasePublicMutableTimeEntity, 추후 도입)는 아직 없어서
     * 그 인프라를 상속받지 않고 이 필드에 직접 @UuidGenerator를 붙였다.
     */
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "upload_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID uploadId;

    @Column(name = "object_key", nullable = false, length = OBJECT_KEY_MAX_LENGTH)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", nullable = false, length = 20)
    private UploadStatus uploadStatus;

    // 상품 안에서의 표시 순서. 여러 장을 순서대로 보여주는 기능은 지금 범위 밖이라 항상 0으로 둔다
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_main", nullable = false)
    private boolean isMain;

    private ProductImage(Long productId, String objectKey) {
        validateProductId(productId);
        validateObjectKey(objectKey);
        this.productId = productId;
        this.objectKey = objectKey;
        this.sortOrder = 0;
        // uploadId 는 여기서 채우지 않는다. @UuidGenerator 가 INSERT 시점에 값을 넣는다(save() 이후에 읽을 수 있다)
        this.uploadStatus = UploadStatus.PENDING;
        this.isMain = false;
    }

    // 업로드 URL을 발급하며 이미지 행을 만든다. 이 시점엔 S3에 실제로 올라갔는지 모른다(PENDING)
    public static ProductImage register(Long productId, String objectKey) {
        return new ProductImage(productId, objectKey);
    }

    // S3 HeadObject로 실제 업로드를 확인한 뒤 호출한다. PENDING 상태에서만 확정할 수 있다
    public void confirm() {
        if (this.uploadStatus != UploadStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 확정할 수 있다: " + this.uploadStatus);
        }
        this.uploadStatus = UploadStatus.CONFIRMED;
    }

    // 대표 이미지로 지정한다. 확정되지 않은 이미지는 대표가 될 수 없다(DB chk_product_image_main과 같은 규칙)
    public void markAsMain() {
        if (this.uploadStatus != UploadStatus.CONFIRMED) {
            throw new IllegalStateException("CONFIRMED 상태에서만 대표로 지정할 수 있다: " + this.uploadStatus);
        }
        this.isMain = true;
    }

    // 대표 지정을 해제한다. 새 대표로 교체하기 전에 기존 대표를 먼저 내릴 때 쓴다
    public void unmarkAsMain() {
        this.isMain = false;
    }

    private static void validateProductId(Long productId) {
        if (productId == null) {
            throw new IllegalArgumentException("productId 는 필수다");
        }
    }

    private static void validateObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey 는 필수다");
        }
        if (objectKey.length() > OBJECT_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "objectKey 는 " + OBJECT_KEY_MAX_LENGTH + "자를 넘을 수 없다: " + objectKey.length());
        }
    }
}
