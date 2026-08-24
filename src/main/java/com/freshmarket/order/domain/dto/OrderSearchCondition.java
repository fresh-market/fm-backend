package com.freshmarket.order.domain.dto;

import com.freshmarket.order.domain.entity.OrderStatus;
import java.time.LocalDate;

// 목록 필터는 모두 선택값이다. 회원 식별자는 인증 정보로만 받고 이 DTO에 넣지 않는다.
public record OrderSearchCondition(
        OrderStatus status,
        LocalDate from,
        LocalDate to
) {
}
