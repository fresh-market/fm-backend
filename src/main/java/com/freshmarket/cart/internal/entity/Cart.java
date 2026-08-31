package com.freshmarket.cart.internal.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 회원당 하나만 존재하는 장바구니다. FK는 다른 도메인 엔티티 연관 대신 식별자 컬럼으로 보관한다.
@Entity
@Table(name = "cart", uniqueConstraints = @UniqueConstraint(name = "uk_cart_member", columnNames = "member_id"))
@AttributeOverride(name = "id", column = @Column(name = "cart_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart extends BaseMutableTimeEntity {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    private Cart(Long memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId 는 필수다");
        }
        this.memberId = memberId;
    }

    public static Cart create(Long memberId) {
        return new Cart(memberId);
    }
}
