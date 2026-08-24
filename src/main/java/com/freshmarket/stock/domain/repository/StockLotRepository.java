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
     * 로트 행들에 쓰기 락을 한 번에 건다(CategoryRepository/MemberRepository의 findByIdForUpdate와
     * 같은 취지). confirm/release는 조회한 available_qty를 그대로 stock_movement 원장에 남기는데,
     * 락 없는 조회 뒤에 별도 UPDATE를 하면 그 사이 다른 트랜잭션이 값을 바꿔 원장이 실제 이력과
     * 어긋날 수 있다. 락을 걸어 읽은 값이 커밋까지 그대로 유지되게 한다.
     *
     * id 오름차순으로 정렬해서 락을 건다 — confirm/release 대상 로트가 여러 개일 때, 호출마다
     * 서로 다른 순서로 로트를 하나씩 잠그면 교착이 날 수 있다(A가 로트10→20 순서로, B가 20→10
     * 순서로 동시에 잠그는 경우). 항상 같은 순서로 한 번에 잠그면 이 경합 자체가 생기지 않는다.
     * 여러 건을 한 번에 조회해 N+1도 함께 없앤다.
     *
     * reserve의 조건부 UPDATE는 이미 한 문장이라(stock.md) 이 락이 필요 없다 — 그쪽은 건드리지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockLot s where s.id in :ids order by s.id")
    List<StockLot> findAllByIdForUpdate(@Param("ids") List<Long> ids);

    // 옵션 ID 목록에 속한 로트 전체를 소비기한 오름차순으로 조회한다 (FEFO 순서)
    List<StockLot> findByProductOptionIdInOrderByExpiryDateAsc(List<Long> productOptionIds);

    // 위와 같되 상태로도 거른다. availableOnly=true일 때 AVAILABLE만 보는 데 쓰인다
    List<StockLot> findByProductOptionIdInAndStatusOrderByExpiryDateAsc(List<Long> productOptionIds,
            LotStatus status);

    /*
     * 로트 하나에 쓰기 락을 건다(CategoryRepository/MemberRepository의 findByIdForUpdate와 같은
     * 취지). 폐기는 조회한 available_qty를 그대로 stock_movement 원장에 남기는데, 락 없는 조회 뒤에
     * 별도 UPDATE를 하면 그 사이 reserve/release 등 다른 트랜잭션이 값을 바꿔 원장이 실제 이력과
     * 어긋날 수 있다. 락을 걸어 읽은 값이 커밋까지 그대로 유지되게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockLot s where s.id = :id")
    Optional<StockLot> findByIdForUpdate(@Param("id") Long id);

    // 그 옵션에 아직 AVAILABLE 상태인 로트가 하나라도 남아있는지. 폐기 뒤 품절 전환 여부 판정에 쓴다
    boolean existsByProductOptionIdAndStatus(Long productOptionId, LotStatus status);
}
