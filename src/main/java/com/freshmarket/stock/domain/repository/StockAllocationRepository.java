package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.entity.AllocationStatus;
import com.freshmarket.stock.domain.entity.StockAllocation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

// 할당(StockAllocation) 기본 CRUD
public interface StockAllocationRepository extends JpaRepository<StockAllocation, Long> {

    // 재시도 감지: 이 주문상품에 이미 만들어진 할당이 있는지 본다(reserve 멱등 처리)
    List<StockAllocation> findByOrderItemId(Long orderItemId);

    // confirm/release 대상 조회: 여러 주문상품의 RESERVED 할당을 한 번에 가져온다
    List<StockAllocation> findByOrderItemIdInAndStatus(List<Long> orderItemIds, AllocationStatus status);
}
