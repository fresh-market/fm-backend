package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.dto.LotDisposedQty;
import com.freshmarket.stock.domain.entity.StockMovement;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 재고 변동 이력(StockMovement) 기본 CRUD
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    // 요청 식별자로 이미 처리된 폐기 이력이 있는지 찾는다. 재시도 감지에 쓰인다(API-5-07)
    Optional<StockMovement> findByRequestId(String requestId);

    /*
     * 로트별 폐기 누계. 캠페인 배치가 소진율에서 폐기분을 빼는 데 쓴다.
     * 후보 로트를 한 번에 넘겨 집계하므로 로트 수와 무관하게 쿼리는 한 번이다.
     * 폐기 이력이 없는 로트는 결과에 아예 안 나오므로 호출부가 0 으로 본다.
     */
    @Query("select new com.freshmarket.stock.domain.dto.LotDisposedQty(m.stockLotId, sum(m.quantity)) "
            + "from StockMovement m "
            + "where m.movementType = com.freshmarket.stock.domain.entity.MovementType.DISPOSE "
            + "and m.stockLotId in :stockLotIds "
            + "group by m.stockLotId")
    List<LotDisposedQty> findDisposedQtyByStockLotIds(@Param("stockLotIds") List<Long> stockLotIds);
}
