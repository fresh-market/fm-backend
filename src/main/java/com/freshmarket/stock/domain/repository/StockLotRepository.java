package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.entity.LotStatus;
import com.freshmarket.stock.domain.entity.StockLot;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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

    /*
     * 만료 배치 대상을 잠그며 조회한다: 소비기한이 date보다 이르면서 아직 status인 로트를 찾는다.
     * 조회 자체에 쓰기 락을 걸어, 같은 로트를 동시에 건드리는 reserve()의 조건부 UPDATE와 순서를
     * 강제한다 — 락 없이 beforeQty를 읽으면, 그 사이 reserve()가 같은 로트를 일부 예약해가도
     * stock_movement 원장에는 예약 전 수량이 그대로 EXPIRE 이력으로 남아 실제 타임라인과 어긋난다.
     *
     * (DI-2-02) 비관적 락을 고른 이유: 이 배치는 하루 한 번만 돌고 대상도 그 사이 새로 소비기한이
     * 지난 로트로 한정돼 있어 잠금을 오래 붙들 만큼 몰릴 일이 없다(저빈도 배치 vs 상시 트래픽인
     * reserve()). 반대로 낙관적 잠금(버전 검증+재시도)을 썼다면 원장(stock_movement)에 남길
     * beforeQty를 다시 읽는 재시도 로직이 필요해 오히려 더 복잡해진다. 운영 트래픽으로 측정한
     * 실측 충돌률은 아직 없다(배포 전) — 배포 후 락 대기 지표가 쌓이면 이 판단을 다시 검토한다.
     *
     * (DI-4-03/PERF-4-03) 청크(Pageable) 단위로 가져온다. 처리된 행은 상태가 AVAILABLE에서
     * EXPIRED로 바뀌어 이 조건에서 자연히 빠지므로, 같은 Pageable(0페이지)을 반복 호출하는 것만으로
     * 다음 청크가 채워진다 — offset 기반이라도 이미 처리한 행이 걸러져 offset 이동이 필요 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockLot s where s.status = :status and s.expiryDate < :date order by s.id")
    List<StockLot> findByStatusAndExpiryDateBefore(@Param("status") LotStatus status, @Param("date") LocalDate date,
            Pageable pageable);

    // 이 옵션에 status인 로트가 하나라도 남아있는지 확인한다. 만료 처리 후 품절 여부 판단에 쓰인다
    boolean existsByProductOptionIdAndStatus(Long productOptionId, LotStatus status);

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
}
