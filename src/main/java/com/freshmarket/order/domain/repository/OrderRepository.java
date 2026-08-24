package com.freshmarket.order.domain.repository;

import com.freshmarket.order.domain.entity.Order;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdAndMemberId(Long id, Long memberId);
}
