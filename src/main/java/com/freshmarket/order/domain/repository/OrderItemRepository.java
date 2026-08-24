package com.freshmarket.order.domain.repository;

import com.freshmarket.order.domain.entity.OrderItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findAllByOrderIdOrderByIdAsc(Long orderId);
}
