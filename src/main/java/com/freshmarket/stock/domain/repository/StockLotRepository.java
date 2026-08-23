package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.entity.StockLot;
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
}
