package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.entity.StockMovement;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 재고 변동 이력(StockMovement) 기본 CRUD
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    // 요청 식별자로 이미 처리된 폐기 이력이 있는지 찾는다. 재시도 감지에 쓰인다(API-5-07)
    Optional<StockMovement> findByRequestId(String requestId);
}
