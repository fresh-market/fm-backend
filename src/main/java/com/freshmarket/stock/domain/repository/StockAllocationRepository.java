package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.entity.AllocationStatus;
import com.freshmarket.stock.domain.entity.StockAllocation;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 할당(StockAllocation) 기본 CRUD
public interface StockAllocationRepository extends JpaRepository<StockAllocation, Long> {

    // 재시도 감지: 이 주문상품에 이미 만들어진 할당이 있는지 본다(reserve 멱등 처리)
    List<StockAllocation> findByOrderItemId(Long orderItemId);

    /*
     * confirm/release 대상 조회: 여러 주문상품의 RESERVED 할당을 한 번에 잠그며 가져온다(DI-2-01).
     * 락 없는 조회였을 때는, 같은 orderItemIds로 진짜 동시에 confirm/release가 두 번 들어오면 (예:
     * 결제 승인 웹훅 중복 전송) 둘 다 status=RESERVED를 읽어버려 stock_movement 원장이 중복 기록될
     * 수 있었다. 여기서 쓰기 락을 걸면 두 번째 호출은 첫 번째가 커밋할 때까지 블로킹되고, 잠금 조회는
     * 항상 최신 커밋 데이터를 보므로(InnoDB) 재개됐을 때 이미 상태가 바뀐 행은 WHERE 절에서 자연히
     * 빠진다 — 순차 재시도 멱등성과 동일한 방식으로 동시 중복 호출도 막힌다.
     * id 오름차순으로 정렬해서 잠근다 — StockLotRepository.findAllByIdForUpdate와 같은 이유로 교착을 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StockAllocation a where a.orderItemId in :orderItemIds and a.status = :status order by a.id")
    List<StockAllocation> findByOrderItemIdInAndStatus(
            @Param("orderItemIds") List<Long> orderItemIds, @Param("status") AllocationStatus status);
}
