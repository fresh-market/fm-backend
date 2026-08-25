package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.entity.CampaignTargetLot;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignTargetLotRepository extends JpaRepository<CampaignTargetLot, Long> {

    // 특정 기준일의 캠페인 대상을 순위대로 조회한다
    List<CampaignTargetLot> findByTargetDateOrderByTargetRankAsc(LocalDate targetDate);

    /*
     * 재실행 시 동일 일자 집계를 덮어쓰기 위해, 먼저 그날 것을 지운다 (daily_sales 와 같은 원칙).
     *
     * 파생 delete 메서드(대상을 조회해 remove() 로 표시만 하고 실제 DELETE 는 플러시 시점까지
     * 미룸) 대신 벌크 @Modifying 으로 즉시 지운다(StockLotRepository.decreaseAvailableQty 와
     * 같은 패턴). campaign_target_lot 은 IDENTITY 채번이라 뒤이은 save() 가 persist() 시점에
     * 즉시 INSERT 를 낸다 — 삭제가 미뤄지면 같은 트랜잭션 안에서 INSERT 가 먼저 나가 아직 안
     * 지워진 옛 행과 uk_campaign_target_date_lot 이 충돌한다. 벌크 삭제는 호출 즉시 DB 에
     * 반영되므로 이 순서 문제가 생기지 않는다.
     */
    @Modifying
    @Query("delete from CampaignTargetLot c where c.targetDate = :targetDate")
    void deleteByTargetDate(@Param("targetDate") LocalDate targetDate);
}
