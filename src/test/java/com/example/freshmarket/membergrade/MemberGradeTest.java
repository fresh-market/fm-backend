package com.example.freshmarket.membergrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MemberGradeTest {

    @Test
    void 등급을_생성하면_전달한_값이_그대로_설정된다() {
        // given, when
        MemberGrade grade = MemberGrade.create("단골", new BigDecimal("5.00"), "3개월 내 5회 이상 구매");

        // then
        assertThat(grade.getName()).isEqualTo("단골");
        assertThat(grade.getDiscountRate()).isEqualByComparingTo("5.00");
        assertThat(grade.getPromotionRule()).isEqualTo("3개월 내 5회 이상 구매");
    }

    @Test
    void 등급명을_변경하면_name만_바뀐다() {
        // given
        MemberGrade grade = MemberGrade.create("기본", new BigDecimal("0.00"), null);

        // when
        grade.rename("VIP");

        // then
        assertThat(grade.getName()).isEqualTo("VIP");
    }

    @Test
    void 할인율을_변경하면_discountRate만_바뀐다() {
        // given
        MemberGrade grade = MemberGrade.create("단골", new BigDecimal("5.00"), "승급 기준");

        // when
        grade.changeDiscountRate(new BigDecimal("10.00"));

        // then
        assertThat(grade.getDiscountRate()).isEqualByComparingTo("10.00");
    }

    @Test
    void 승급기준을_변경하면_promotionRule만_바뀐다() {
        // given
        MemberGrade grade = MemberGrade.create("단골", new BigDecimal("5.00"), "기존 기준");

        // when
        grade.changePromotionRule("6개월 내 10회 이상 구매");

        // then
        assertThat(grade.getPromotionRule()).isEqualTo("6개월 내 10회 이상 구매");
    }

    @Test
    void id가_없는_등급끼리는_같지_않다() {
        // given
        MemberGrade a = MemberGrade.create("기본", new BigDecimal("0.00"), null);
        MemberGrade b = MemberGrade.create("기본", new BigDecimal("0.00"), null);

        // when, then
        assertThat(a).isNotEqualTo(b);
    }
}