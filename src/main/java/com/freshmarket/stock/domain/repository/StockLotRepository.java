package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.entity.LotStatus;
import com.freshmarket.stock.domain.entity.StockLot;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /*
     * 로트 행 자체에 쓰기 락을 건다(CategoryRepository/MemberRepository와 같은 패턴).
     * confirm/release는 조회한 available_qty를 그대로 stock_movement 원장에 남기는데, 락 없는
     * 조회 뒤에 별도 UPDATE를 하면 그 사이 다른 트랜잭션이 값을 바꿔 원장이 실제 이력과 어긋날 수
     * 있다. 락을 걸어 읽은 값이 커밋까지 그대로 유지되게 한다. reserve의 조건부 UPDATE는 이미
     * 한 문장이라(stock.md) 이 락이 필요 없다 — 그쪽은 건드리지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockLot s where s.id = :id")
    Optional<StockLot> findByIdForUpdate(@Param("id") Long id);
}
