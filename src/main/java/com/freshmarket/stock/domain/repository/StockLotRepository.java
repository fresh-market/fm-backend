package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.entity.LotStatus;
import com.freshmarket.stock.domain.entity.StockLot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 로트(StockLot) 기본 CRUD
public interface StockLotRepository extends JpaRepository<StockLot, Long> {

    /*
     * 요청 식별자로 이미 등록된 로트를 찾는다. 재시도 감지에 쓰인다.
     * productOptionId까지 같이 봐서, 클라이언트가 같은 requestId를 다른 옵션에 잘못 재사용해도
     * 엉뚱한 옵션의 로트를 재시도 응답으로 돌려주지 않는다.
     */
    Optional<StockLot> findByRequestIdAndProductOptionId(String requestId, Long productOptionId);

    // 옵션 ID 목록에 속한 로트 전체를 소비기한 오름차순으로 조회한다 (FEFO 순서)
    List<StockLot> findByProductOptionIdInOrderByExpiryDateAsc(List<Long> productOptionIds);

    // 위와 같되 상태로도 거른다. availableOnly=true일 때 AVAILABLE만 보는 데 쓰인다
    List<StockLot> findByProductOptionIdInAndStatusOrderByExpiryDateAsc(List<Long> productOptionIds,
            LotStatus status);
}
