package com.freshmarket.order.domain.entity;

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

@Entity
@Table(name = "order_item")
@AttributeOverride(name = "id", column = @Column(name = "order_item_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseMutableTimeEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "source_cart_item_id")
    private Long sourceCartItemId;

    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Column(name = "name_snapshot", nullable = false, length = 255)
    private String nameSnapshot;

    @Column(name = "option_name_snapshot", nullable = false, length = 100)
    private String optionNameSnapshot;

    @Column(name = "unit_price", nullable = false)
    private int unitPrice;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "member_coupon_id")
    private Long memberCouponId;

    @Column(name = "coupon_id")
    private Long couponId;

    @Column(name = "coupon_discount", nullable = false)
    private int couponDiscount;

    @Column(name = "discount_amount", nullable = false)
    private int discountAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_status", nullable = false, length = 30)
    private OrderItemStatus itemStatus;

    private OrderItem(Long orderId, Long sourceCartItemId, Long productOptionId, String nameSnapshot, String optionNameSnapshot,
                      int unitPrice, int qty, Long memberId, Long memberCouponId, Long couponId,
                      int couponDiscount, int discountAmount, OrderItemStatus itemStatus) {
        validate(orderId, sourceCartItemId, productOptionId, nameSnapshot, optionNameSnapshot, unitPrice, qty,
                memberId, memberCouponId, couponId, couponDiscount, discountAmount, itemStatus);
        this.orderId = orderId;
        this.sourceCartItemId = sourceCartItemId;
        this.productOptionId = productOptionId;
        this.nameSnapshot = nameSnapshot;
        this.optionNameSnapshot = optionNameSnapshot;
        this.unitPrice = unitPrice;
        this.qty = qty;
        this.memberId = memberId;
        this.memberCouponId = memberCouponId;
        this.couponId = couponId;
        this.couponDiscount = couponDiscount;
        this.discountAmount = discountAmount;
        this.itemStatus = itemStatus;
    }

    public static OrderItem place(Long orderId, Long productOptionId, String nameSnapshot,
                                  String optionNameSnapshot, int unitPrice, int qty,
                                  Long memberId, Long memberCouponId, Long couponId, int couponDiscount,
                                  int discountAmount, OrderItemStatus itemStatus) {
        return new OrderItem(orderId, null, productOptionId, nameSnapshot, optionNameSnapshot, unitPrice,
                qty, memberId, memberCouponId, couponId, couponDiscount, discountAmount, itemStatus);
    }

    public static OrderItem place(OrderItemPlacement placement) {
        if (placement == null) {
            throw new IllegalArgumentException("주문 항목 생성 정보는 필수입니다.");
        }
        return new OrderItem(placement.orderId(), placement.sourceCartItemId(), placement.productOptionId(),
                placement.nameSnapshot(), placement.optionNameSnapshot(), placement.unitPrice(), placement.qty(),
                placement.memberId(), null, null, 0, placement.discountAmount(), OrderItemStatus.ORDERED);
    }

    public int getLineAmount() {
        return Math.multiplyExact(unitPrice, qty) - discountAmount;
    }

    public void cancel() {
        if (itemStatus == OrderItemStatus.CANCELED) {
            return;
        }
        if (itemStatus != OrderItemStatus.ORDERED) {
            throw new IllegalStateException("현재 주문 상품 상태에서는 취소할 수 없습니다.");
        }
        this.itemStatus = OrderItemStatus.CANCELED;
    }

    private static void validate(Long orderId, Long sourceCartItemId, Long productOptionId, String nameSnapshot,
                                 String optionNameSnapshot, int unitPrice, int qty, Long memberId,
                                 Long memberCouponId, Long couponId, int couponDiscount, int discountAmount,
                                 OrderItemStatus itemStatus) {
        requirePositive(orderId, "orderId");
        if (sourceCartItemId != null) {
            requirePositive(sourceCartItemId, "sourceCartItemId");
        }
        requirePositive(productOptionId, "productOptionId");
        requireText(nameSnapshot, 255, "nameSnapshot");
        requireText(optionNameSnapshot, 100, "optionNameSnapshot");
        requireNonNegative(unitPrice, "unitPrice");
        if (qty <= 0) {
            throw new IllegalArgumentException("qty는 0보다 커야 합니다.");
        }
        requirePositive(memberId, "memberId");
        requireNonNegative(couponDiscount, "couponDiscount");
        requireNonNegative(discountAmount, "discountAmount");
        if (itemStatus == null) {
            throw new IllegalArgumentException("itemStatus는 필수입니다.");
        }
        int grossAmount = Math.multiplyExact(unitPrice, qty);
        if (couponDiscount > discountAmount || discountAmount > grossAmount) {
            throw new IllegalArgumentException("주문 상품 할인 금액이 올바르지 않습니다.");
        }
        if (memberCouponId == null && (couponId != null || couponDiscount != 0)) {
            throw new IllegalArgumentException("쿠폰 없이 쿠폰 정보가 있을 수 없습니다.");
        }
        if (memberCouponId != null && couponId == null) {
            throw new IllegalArgumentException("상품 쿠폰에는 couponId가 필요합니다.");
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
}
