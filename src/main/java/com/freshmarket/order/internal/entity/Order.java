package com.freshmarket.order.internal.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "orders")
@AttributeOverride(name = "id", column = @Column(name = "order_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseMutableTimeEntity {

    @Column(name = "order_no", nullable = false, length = 30)
    private String orderNo;

    @Column(name = "request_id", length = 64, unique = true)
    private String requestId;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OrderStatus status;

    @Column(name = "product_amount", nullable = false)
    private int productAmount;

    @Column(name = "discount_amount", nullable = false)
    private int discountAmount;

    @Column(name = "member_coupon_id")
    private Long memberCouponId;

    // coupon 도메인의 내부 enum에 의존하지 않도록 DB 스냅샷 값은 문자열로 보관한다.
    @Column(name = "coupon_scope", length = 20)
    private String couponScope;

    @Column(name = "coupon_discount", nullable = false)
    private int couponDiscount;

    @Column(name = "shipping_fee", nullable = false)
    private int shippingFee;

    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Column(name = "ship_recipient", nullable = false, length = 50)
    private String shipRecipient;

    @Column(name = "ship_phone", nullable = false, length = 20)
    private String shipPhone;

    @Column(name = "ship_zipcode", nullable = false, length = 10)
    private String shipZipcode;

    @Column(name = "ship_address", nullable = false, length = 500)
    private String shipAddress;

    @Column(name = "ship_message", length = 255)
    private String shipMessage;

    @Column(name = "ordered_at", nullable = false)
    private LocalDateTime orderedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private Order(Long memberId, String orderNo, String requestId, String requestHash, OrderStatus status, int productAmount,
                  int discountAmount, Long memberCouponId, String couponScope, int couponDiscount,
                  int shippingFee, int totalAmount, String shipRecipient, String shipPhone,
                  String shipZipcode, String shipAddress, String shipMessage, LocalDateTime orderedAt) {
        validate(memberId, orderNo, requestId, requestHash, productAmount, discountAmount, memberCouponId, couponScope,
                couponDiscount, shippingFee, totalAmount, shipRecipient, shipPhone, shipZipcode,
                shipAddress, shipMessage, orderedAt);
        this.memberId = memberId;
        this.orderNo = orderNo;
        this.requestId = requestId;
        this.requestHash = requestHash;
        this.status = status;
        this.productAmount = productAmount;
        this.discountAmount = discountAmount;
        this.memberCouponId = memberCouponId;
        this.couponScope = couponScope;
        this.couponDiscount = couponDiscount;
        this.shippingFee = shippingFee;
        this.totalAmount = totalAmount;
        this.shipRecipient = shipRecipient;
        this.shipPhone = shipPhone;
        this.shipZipcode = shipZipcode;
        this.shipAddress = shipAddress;
        this.shipMessage = shipMessage;
        this.orderedAt = orderedAt;
    }

    // 주문은 결제 전 상태로만 생성한다. 이후 상태 변경은 결제/배송 흐름의 전용 메서드로 제한한다.
    public static Order place(Long memberId, String orderNo, int productAmount,
                              int discountAmount, Long memberCouponId, String couponScope,
                              int couponDiscount, int shippingFee, int totalAmount, String shipRecipient,
                              String shipPhone, String shipZipcode, String shipAddress,
                              String shipMessage, LocalDateTime orderedAt) {
        return Order.builder()
                .memberId(memberId)
                .orderNo(orderNo)
                .status(OrderStatus.PAYMENT_PENDING)
                .productAmount(productAmount)
                .discountAmount(discountAmount)
                .memberCouponId(memberCouponId)
                .couponScope(couponScope)
                .couponDiscount(couponDiscount)
                .shippingFee(shippingFee)
                .totalAmount(totalAmount)
                .shipRecipient(shipRecipient)
                .shipPhone(shipPhone)
                .shipZipcode(shipZipcode)
                .shipAddress(shipAddress)
                .shipMessage(shipMessage)
                .orderedAt(orderedAt)
                .build();
    }

    public static Order place(OrderPlacement placement) {
        if (placement == null) {
            throw new IllegalArgumentException("주문 생성 정보는 필수입니다.");
        }
        return Order.builder()
                .memberId(placement.memberId())
                .orderNo(placement.orderNo())
                .requestId(placement.requestId())
                .requestHash(placement.requestHash())
                .status(OrderStatus.PAYMENT_PENDING)
                .productAmount(placement.productAmount())
                .discountAmount(placement.discountAmount())
                .couponDiscount(0)
                .shippingFee(placement.shippingFee())
                .totalAmount(placement.totalAmount())
                .shipRecipient(placement.shipRecipient())
                .shipPhone(placement.shipPhone())
                .shipZipcode(placement.shipZipcode())
                .shipAddress(placement.shipAddress())
                .shipMessage(placement.shipMessage())
                .orderedAt(placement.orderedAt())
                .build();
    }

    /*
     * orderNo는 NOT NULL+UNIQUE(uk_order_no)라 insert 시점에 값이 있어야 하는데, order_id(PK)는
     * IDENTITY라 insert 이후에만 안다. OrderCreateService가 OrderNoGenerator로 만든 임시
     * 고유값으로 먼저 저장해 PK를 받은 뒤, 이 메서드로 "orderNo = orderId" 정책값을 되돌려
     * 채운다(주문번호 체계를 아직 정하지 않은 지금 단계에서 팀이 정한 임시 정책).
     */
    public void assignOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank() || orderNo.length() > 30) {
            throw new IllegalArgumentException("orderNo는 필수이며 최대 길이를 넘을 수 없습니다.");
        }
        this.orderNo = orderNo;
    }

    public void markPaid() {
        if (status == OrderStatus.PAID) {
            return;
        }
        requireStatus(OrderStatus.PAYMENT_PENDING, "결제 완료");
        this.status = OrderStatus.PAID;
    }

    public void cancel() {
        if (status == OrderStatus.CANCELED) {
            return;
        }
        requireStatus(OrderStatus.PAYMENT_PENDING, "주문 취소");
        this.status = OrderStatus.CANCELED;
    }

    private static void validate(Long memberId, String orderNo, String requestId, String requestHash,
                                 int productAmount, int discountAmount,
                                 Long memberCouponId, String couponScope, int couponDiscount,
                                 int shippingFee, int totalAmount, String shipRecipient, String shipPhone,
                                 String shipZipcode, String shipAddress, String shipMessage,
                                 LocalDateTime orderedAt) {
        requirePositive(memberId, "memberId");
        requireText(orderNo, 30, "orderNo");
        if ((requestId == null) != (requestHash == null)) {
            throw new IllegalArgumentException("주문 요청 식별자와 요청 해시는 함께 있어야 합니다.");
        }
        if (requestId != null) {
            requireText(requestId, 64, "requestId");
            if (!requestHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("requestHash는 SHA-256 16진수여야 합니다.");
            }
        }
        requireNonNegative(productAmount, "productAmount");
        requireNonNegative(discountAmount, "discountAmount");
        requireNonNegative(couponDiscount, "couponDiscount");
        requireNonNegative(shippingFee, "shippingFee");
        requireNonNegative(totalAmount, "totalAmount");
        if (discountAmount > productAmount || couponDiscount > discountAmount
                || totalAmount != productAmount - discountAmount + shippingFee) {
            throw new IllegalArgumentException("주문 금액 구성이 올바르지 않습니다.");
        }
        if (memberCouponId == null && (couponScope != null || couponDiscount != 0)) {
            throw new IllegalArgumentException("쿠폰 없이 쿠폰 정보가 있을 수 없습니다.");
        }
        if (memberCouponId != null && !"ORDER".equals(couponScope)) {
            throw new IllegalArgumentException("주문 쿠폰의 범위는 ORDER여야 합니다.");
        }
        requireText(shipRecipient, 50, "shipRecipient");
        requireText(shipPhone, 20, "shipPhone");
        requireText(shipZipcode, 10, "shipZipcode");
        requireText(shipAddress, 500, "shipAddress");
        if (shipMessage != null && shipMessage.length() > 255) {
            throw new IllegalArgumentException("shipMessage 길이는 255 이하여야 합니다.");
        }
        if (orderedAt == null) {
            throw new IllegalArgumentException("orderedAt은 필수입니다.");
        }
    }

    private static void requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + "은 0보다 커야 합니다.");
        }
    }

    private static void requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + "은 0 이상이어야 합니다.");
        }
    }

    private static void requireText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "은 필수이며 최대 길이를 넘을 수 없습니다.");
        }
    }

    private void requireStatus(OrderStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException("현재 주문 상태에서는 " + action + "할 수 없습니다.");
        }
    }
}
