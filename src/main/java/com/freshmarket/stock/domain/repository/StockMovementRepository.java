package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.entity.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;

// 재고 변동 이력(StockMovement) 기본 CRUD
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {
}
