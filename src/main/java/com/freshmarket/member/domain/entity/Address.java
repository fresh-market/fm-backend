package com.freshmarket.member.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 배송지. Member와 마찬가지로 @ManyToOne 대신 memberId(Long)를 직접 들고 있다.
 *
 * "기본 배송지 1개만 허용"은 DDL과 같은 방식(MySQL generated column + UNIQUE)으로 DB 레벨에서도
 * 강제한다 — isDefaultKey 참고. 생성은 @Builder(access=PRIVATE) + register() 정적 팩토리로만
 * 열어둔다.
 *
 * PK 컬럼명은 스키마 전체 컨벤션(schema-design-rationale.md)대로 address_id다 —
 * BaseMutableTimeEntity의 id 필드는 컬럼명을 "id"로 매핑하므로, @AttributeOverride로
 * 실제 DDL의 PK 컬럼명에 맞춰준다.
 */
@Entity
@Getter
@Table(name = "address")
@AttributeOverride(name = "id", column = @Column(name = "address_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address extends BaseMutableTimeEntity {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false, length = 50)
    private String recipient;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 10)
    private String zipcode;

    @Column(name = "road_address", nullable = false, length = 255)
    private String roadAddress;

    // DDL은 NULL 허용(선택 항목).
    @Column(name = "detail_address", length = 255)
    private String detailAddress;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "is_default_key", insertable = false, updatable = false, unique = true,
            columnDefinition = "BIGINT GENERATED ALWAYS AS (CASE WHEN is_default THEN member_id ELSE NULL END) STORED")
    private Long isDefaultKey;

    @Builder(access = AccessLevel.PRIVATE)
    private Address(Long memberId, String recipient, String phone, String zipcode,
                     String roadAddress, String detailAddress, boolean isDefault) {
        this.memberId = memberId;
        this.recipient = recipient;
        this.phone = phone;
        this.zipcode = zipcode;
        this.roadAddress = roadAddress;
        this.detailAddress = detailAddress;
        this.isDefault = isDefault;
    }

    /** 배송지 등록 — 유일한 생성 진입점. */
    public static Address register(Long memberId, String recipient, String phone, String zipcode,
                                    String roadAddress, String detailAddress, boolean isDefault) {
        return Address.builder()
                .memberId(memberId)
                .recipient(recipient)
                .phone(phone)
                .zipcode(zipcode)
                .roadAddress(roadAddress)
                .detailAddress(detailAddress)
                .isDefault(isDefault)
                .build();
    }

    public void markAsDefault() {
        this.isDefault = true;
    }

    public void unmarkAsDefault() {
        this.isDefault = false;
    }

    public void update(String recipient, String phone, String zipcode, String roadAddress, String detailAddress) {
        this.recipient = recipient;
        this.phone = phone;
        this.zipcode = zipcode;
        this.roadAddress = roadAddress;
        this.detailAddress = detailAddress;
    }

    @Override
    public String toString() {
        return "Address{id=%s, memberId=%s, isDefault=%s}".formatted(getId(), memberId, isDefault);
    }
}
