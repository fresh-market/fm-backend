package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.entity.LotStatus;
import com.freshmarket.stock.domain.entity.StockLot;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockLot s where s.status = :status and s.expiryDate < :date order by s.id")
    List<StockLot> findByStatusAndExpiryDateBefore(@Param("status") LotStatus status, @Param("date") LocalDate date);

    // 이 옵션에 status인 로트가 하나라도 남아있는지 확인한다. 만료 처리 후 품절 여부 판단에 쓰인다
    boolean existsByProductOptionIdAndStatus(Long productOptionId, LotStatus status);
}
