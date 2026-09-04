package com.freshmarket.stock.internal.repository;

import com.freshmarket.stock.internal.entity.CampaignTargetLot;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignTargetLotRepository extends JpaRepository<CampaignTargetLot, Long> {

    // 특정 기준일의 캠페인 대상을 순위대로 조회한다
    List<CampaignTargetLot> findByTargetDateOrderByTargetRankAsc(LocalDate targetDate);

    /*
     * 회원용 조회의 커서 페이지네이션. target_rank 는 기준일 안에서 1부터 순차라 커서 축으로 쓴다
     * (idx_campaign_target_date(target_date, target_rank) 를 그대로 탄다).
     * 첫 페이지는 afterRank=0 으로 부른다.
     */
    List<CampaignTargetLot> findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
            LocalDate targetDate, int afterRank, Pageable pageable);

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

    /*
     * 그날 확정본의 버전. 회원용 조회가 캐시 키에 넣는다.
     *
     * 재실행하면 당일분을 지우고 다시 넣으므로 새 행의 id 가 더 크다. 그래서 MAX(id) 하나로
     * "몇 번째 확정본인가" 를 나타낼 수 있다. 확정본이 바뀌면 캐시 키가 통째로 달라져
     * 옛 항목은 아무도 찾지 않게 된다 — 로컬 캐시라 인스턴스별로 비울 방법이 없는 문제를
     * 무효화 대신 키 분리로 푼다.
     *
     * uk_campaign_target_date_lot(target_date, stock_lot_id) 을 탄다. InnoDB 보조 인덱스는
     * PK 를 함께 담으므로 행 본문을 읽지 않는다.
     *
     * 그날 대상이 없으면 null 이다. 그때는 캐시할 것도 없다(빈 결과는 담지 않는다).
     */
    @Query("select max(c.id) from CampaignTargetLot c where c.targetDate = :targetDate")
    Long findConfirmedVersion(@Param("targetDate") LocalDate targetDate);
}
