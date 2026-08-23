package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.entity.LotStatus;
import com.freshmarket.stock.domain.entity.StockLot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 로트(StockLot) 기본 CRUD
public interface StockLotRepository extends JpaRepository<StockLot, Long> {

    /*
     * 요청 식별자로 이미 등록된 로트를 찾는다. 재시도 감지에 쓰인다.
     * productOptionId까지 같이 봐서, 클라이언트가 같은 requestId를 다른 옵션에 잘못 재사용해도
     * 엉뚱한 옵션의 로트를 재시도 응답으로 돌려주지 않는다.
     */
    Optional<StockLot> findByRequestIdAndProductOptionId(String requestId, Long productOptionId);

    // FEFO 배분 순서. idx_lot_fefo(product_option_id, status, expiry_date)를 그대로 탄다
    List<StockLot> findByProductOptionIdAndStatusOrderByExpiryDateAsc(Long productOptionId, LotStatus status);

    /*
     * 가용 수량을 조건부로 줄인다(stock.md). WHERE 절의 availableQty >= qty가 읽고 쓰는 사이 없이
     * 부족/경합을 한 문장으로 걸러낸다. 영향받은 행이 0이면 그 사이 다른 요청이 가져갔거나 애초에
     * 부족했다는 뜻 — 호출부가 다음 로트로 넘어가거나 재고 부족으로 처리한다.
     */
    @Modifying
    @Query("update StockLot s set s.availableQty = s.availableQty - :qty where s.id = :id and s.availableQty >= :qty")
    int decreaseAvailableQty(@Param("id") Long id, @Param("qty") int qty);

    // 가용 수량을 복원한다(release). 상한은 chk_lot_qty(available_qty <= initial_qty)가 DB에서 막는다
    @Modifying
    @Query("update StockLot s set s.availableQty = s.availableQty + :qty where s.id = :id")
    int increaseAvailableQty(@Param("id") Long id, @Param("qty") int qty);
}
