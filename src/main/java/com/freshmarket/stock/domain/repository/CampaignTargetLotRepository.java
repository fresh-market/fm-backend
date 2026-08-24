package com.freshmarket.stock.domain.repository;

import com.freshmarket.stock.domain.entity.CampaignTargetLot;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignTargetLotRepository extends JpaRepository<CampaignTargetLot, Long> {

    // 특정 기준일의 캠페인 대상을 순위대로 조회한다
    List<CampaignTargetLot> findByTargetDateOrderByTargetRankAsc(LocalDate targetDate);

    // 재실행 시 동일 일자 집계를 덮어쓰기 위해, 먼저 그날 것을 지운다 (daily_sales 와 같은 원칙)
    void deleteByTargetDate(LocalDate targetDate);
}
