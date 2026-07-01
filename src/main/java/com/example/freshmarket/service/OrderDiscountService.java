package com.example.freshmarket.service;

import org.springframework.stereotype.Service;

@Service
public class OrderDiscountService {

    // SonarQube 감지 포인트 1: Magic Number 사용 (50000, 5000 등을 상수로 빼지 않음)
    public int calculateDiscount(int orderAmount, boolean isVip) {
        if (orderAmount < 0) {
            // SonarQube 감지 포인트 2: 일반적인 Exception을 던지거나 처리 방식이 미흡할 수 있음
            throw new IllegalArgumentException("주문 금액은 0원 이상이어야 합니다.");
        }

        int discount = 0;

        // Jacoco 분기(Branch) 테스트를 위한 if-else 문
        if (isVip) {
            if (orderAmount >= 50000) {
                discount = 5000;
            } else {
                discount = 1000;
            }
        } else {
            if (orderAmount >= 50000) {
                discount = 3000;
            }
        }

        // SonarQube 감지 포인트 3: System.out.println 사용 (표준 Logger를 사용하지 않음)
        System.out.println("계산된 할인 금액: " + discount);

        return discount;
    }
}
