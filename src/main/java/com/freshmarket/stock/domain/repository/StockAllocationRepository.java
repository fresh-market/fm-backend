package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.entity.AllocationStatus;
import com.freshmarket.stock.domain.entity.StockAllocation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 할당(StockAllocation) 기본 CRUD
public interface StockAllocationRepository extends JpaRepository<StockAllocation, Long> {

    /*
     * 재시도 감지: 이 주문상품들 중 이미 할당이 만들어진 게 있는지 한 번에 본다(reserve 멱등 처리).
     * 기존에는 orderItemId마다 개별 조회했는데, IN 절 하나로 묶어 주문 항목 수만큼 나가던 쿼리를
     * 요청당 1회로 줄인다. status는 따지지 않는다 — 예전에도 allocation이 하나라도 있으면(상태
     * 무관) 재예약을 건너뛰었으므로, 그 동작을 그대로 유지한다.
     */
    @Query("""
            select distinct a.orderItemId
            from StockAllocation a
            where a.orderItemId in :orderItemIds
            """)
    Set<Long> findOrderItemIdsWithAllocation(@Param("orderItemIds") List<Long> orderItemIds);

    /*
     * confirm/release 대상 RESERVED 할당을 잠그며 조회한다(DI-2-01). 락이 없으면 같은 orderItemIds로
     * 동시에 두 번 호출될 때(예: 결제 웹훅 중복 전송) 둘 다 RESERVED를 읽어 stock_movement 원장이
     * 중복 기록될 수 있다. id 오름차순으로 잠가 교착을 막는다(StockLotRepository.findAllByIdForUpdate와 같은 이유).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StockAllocation a where a.orderItemId in :orderItemIds and a.status = :status order by a.id")
    List<StockAllocation> findByOrderItemIdInAndStatus(
            @Param("orderItemIds") List<Long> orderItemIds, @Param("status") AllocationStatus status);
}
