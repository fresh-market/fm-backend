package com.example.freshmarket.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderDiscountServiceTest {

    private final OrderDiscountService discountService = new OrderDiscountService();

    @Test
    @DisplayName("VIP 고객이 5만원 이상 구매 시 5000원 할인된다")
    void calculateDiscount_vipHighAmount() {
        int result = discountService.calculateDiscount(60000, true);
        assertEquals(5000, result);
    }

    @Test
    @DisplayName("일반 고객이 5만원 미만 구매 시 할인이 없다")
    void calculateDiscount_normalLowAmount() {
        int result = discountService.calculateDiscount(30000, false);
        assertEquals(0, result);
    }

    // 🎯 일부러 작성하지 않은 테스트 케이스들 (Jacoco가 잡아낼 부분):
    // 1. 주문 금액이 0원 미만일 때 예외(Exception)가 터지는지?
    // 2. VIP인데 5만원 미만으로 구매했을 때?
    // 3. 일반 고객인데 5만원 이상 구매했을 때?
}
