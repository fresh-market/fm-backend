package com.freshmarket.order.internal.repository;

import com.freshmarket.order.internal.entity.Order;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdAndMemberId(Long id, Long memberId);

    // 주문 생성 요청 재시도(같은 requestId)를 같은 주문으로 수렴시키기 위한 조회다.
    Optional<Order> findByRequestId(String requestId);
}
