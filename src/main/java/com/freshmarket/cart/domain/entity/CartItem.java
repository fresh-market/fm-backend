package com.freshmarket.cart.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

// 장바구니의 옵션별 수량. 같은 카트에 같은 옵션은 하나의 행으로만 유지한다.
@Entity
@Table(name = "cart_item", uniqueConstraints = @UniqueConstraint(
        name = "uk_cart_option", columnNames = {"cart_id", "product_option_id"}))
@AttributeOverride(name = "id", column = @Column(name = "cart_item_id"))
@Check(name = "chk_cartitem_qty", constraints = "qty > 0")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem extends BaseMutableTimeEntity {

    @Column(name = "cart_id", nullable = false)
    private Long cartId;

    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Column(nullable = false)
    private int qty;

    private CartItem(Long cartId, Long productOptionId, int qty) {
        validateCartId(cartId);
        validateProductOptionId(productOptionId);
        validateQty(qty);
        this.cartId = cartId;
        this.productOptionId = productOptionId;
        this.qty = qty;
    }

    public static CartItem add(Long cartId, Long productOptionId, int qty) {
        return new CartItem(cartId, productOptionId, qty);
    }

    public void increaseQty(int qty) {
        validateQty(qty);
        this.qty = Math.addExact(this.qty, qty);
    }

    public void changeQty(int qty) {
        validateQty(qty);
        this.qty = qty;
    }

    private static void validateCartId(Long cartId) {
        if (cartId == null) {
            throw new IllegalArgumentException("cartId 는 필수다");
        }
    }

    private static void validateProductOptionId(Long productOptionId) {
        if (productOptionId == null) {
            throw new IllegalArgumentException("productOptionId 는 필수다");
        }
    }

    private static void validateQty(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty 는 1 이상이어야 한다: " + qty);
        }
    }
}
