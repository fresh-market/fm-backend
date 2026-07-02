package com.example.freshmarket.membergrade;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(name = "member_grade")
public class MemberGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "grade_id")
    private Long id;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "discount_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountRate;

    @Column(name = "promotion_rule", length = 255)
    private String promotionRule;

    protected MemberGrade() {
        // JPA가 프록시, 리플렉션으로 사용하는 기본 생성자. 외부에서 직접 호출하지 않는다.
    }

    private MemberGrade(String name, BigDecimal discountRate, String promotionRule) {
        this.name = name;
        this.discountRate = discountRate;
        this.promotionRule = promotionRule;
    }

    // 이름 있는 정적 팩터리로 등급 생성 의도를 드러낸다
    public static MemberGrade create(String name, BigDecimal discountRate, String promotionRule) {
        return new MemberGrade(name, discountRate, promotionRule);
    }

    // Setter 대신 도메인 메서드로 변경 의도를 드러낸다
    public void rename(String name) {
        this.name = name;
    }

    public void changeDiscountRate(BigDecimal discountRate) {
        this.discountRate = discountRate;
    }

    public void changePromotionRule(String promotionRule) {
        this.promotionRule = promotionRule;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getDiscountRate() {
        return discountRate;
    }

    public String getPromotionRule() {
        return promotionRule;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemberGrade memberGrade)) return false;
        return Objects.equals(id, memberGrade.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MemberGrade{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", discountRate=" + discountRate +
                '}';
    }
}