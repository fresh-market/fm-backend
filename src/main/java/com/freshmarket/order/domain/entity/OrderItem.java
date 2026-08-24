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

    private OrderItem(Long orderId, Long productOptionId, String nameSnapshot, String optionNameSnapshot,
                      int unitPrice, int qty, Long memberId, Long memberCouponId, Long couponId,
                      int couponDiscount, int discountAmount, OrderItemStatus itemStatus) {
        this.orderId = orderId;
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
        return new OrderItem(orderId, productOptionId, nameSnapshot, optionNameSnapshot, unitPrice,
                qty, memberId, memberCouponId, couponId, couponDiscount, discountAmount, itemStatus);
    }

    public int getLineAmount() {
        return Math.multiplyExact(unitPrice, qty) - discountAmount;
    }
}
