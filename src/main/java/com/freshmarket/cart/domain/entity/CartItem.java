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

    // DTO의 @Max와 같은 값이다. @Valid는 단건 요청값만 본다 — increaseQty처럼 기존 값에 더한
    // "결과"가 상한을 넘는 경우까지는 못 막으므로 여기서 한 번 더 막는다(두 번째 방어선).
    private static final int MAX_QTY = 999;

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
        // 두 값 다 validateQty를 거쳐 MAX_QTY 이하이므로 Math.addExact 자체가 오버플로할 일은
        // 없다 — 그래도 합계가 상한을 넘는지는 별도로 막는다(예: 900 + 900).
        int newQty = Math.addExact(this.qty, qty);
        if (newQty > MAX_QTY) {
            throw new IllegalArgumentException("qty 합계는 " + MAX_QTY + " 이하이어야 한다: " + newQty);
        }
        this.qty = newQty;
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
        if (qty <= 0 || qty > MAX_QTY) {
            throw new IllegalArgumentException("qty 는 1 이상 " + MAX_QTY + " 이하이어야 한다: " + qty);
        }
    }
}