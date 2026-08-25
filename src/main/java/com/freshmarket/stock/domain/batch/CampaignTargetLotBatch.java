package com.freshmarket.stock.domain.batch;

import com.freshmarket.stock.domain.TurnoverRateCalculator;
import com.freshmarket.stock.domain.dto.CampaignTargetLotCandidate;
import com.freshmarket.stock.domain.entity.CampaignTargetLot;
import com.freshmarket.stock.domain.repository.CampaignTargetLotRepository;
import com.freshmarket.stock.domain.repository.StockLotQueryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/*
 * 선착순 쿠폰 캠페인 대상 로트를 매일 자정에 확정한다.
 *
 * 조건: 소비기한 D-10 이내 + 소진율 하위 20% + 잔여재고(availableQty) 30 이상,
 * 소진율 오름차순 상위 3건. 요구사항 원문 그대로다.
 *
 * batch 프로필에서만 뜬다 (INF-1-10, ArchitectureTest 로 강제됨). 분산 락이 없어
 * 프로필이 유일한 방어선이라, 이게 빠지면 앱 서버 여러 대가 같은 로트를 동시에 집는다.
 */
@Component
@Profile("batch")
@RequiredArgsConstructor
public class CampaignTargetLotBatch {

    private static final int WITHIN_DAYS = 10;
    private static final int MIN_AVAILABLE_QTY = 30;
    // 하위 20% = 1/5. double 0.2 곱셈은 부동소수점 오차로 ceil() 결과가 하나 더 잘릴 수 있어
    // 정수 나눗셈으로 대신한다 (EJ-8-04)
    private static final int LOW_TURNOVER_PERCENTILE_DIVISOR = 5;
    private static final int TARGET_COUNT = 3;

    private final StockLotQueryRepository stockLotQueryRepository;
    private final CampaignTargetLotRepository campaignTargetLotRepository;
    private final Clock clock;

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void run() {
        LocalDate today = LocalDate.now(clock);
        LocalDate withinDate = today.plusDays(WITHIN_DAYS);

        campaignTargetLotRepository.deleteByTargetDate(today); // 재실행 시 동일 일자 집계는 덮어쓴다

        List<CampaignTargetLotCandidate> candidates =
                stockLotQueryRepository.findCandidatesExpiringBy(withinDate);

        List<TurnoverRatedCandidate> rated = candidates.stream()
                .filter(c -> c.initialQty() > 0)                        // 확보 재고 0이면 산출 대상 제외
                .filter(c -> c.availableQty() >= MIN_AVAILABLE_QTY)      // 잔여재고 30 이상
                .map(c -> new TurnoverRatedCandidate(
                        c, TurnoverRateCalculator.calculate(c.initialQty(), c.availableQty())))
                .sorted(Comparator.comparing(TurnoverRatedCandidate::turnoverRate))
                .toList();

        // ceil(n / 5) 를 정수 나눗셈으로 계산한다: (n + divisor - 1) / divisor
        int lowTurnoverCutoff = (rated.size() + LOW_TURNOVER_PERCENTILE_DIVISOR - 1) / LOW_TURNOVER_PERCENTILE_DIVISOR;
        List<TurnoverRatedCandidate> lowTurnoverGroup = rated.subList(
                0, Math.min(lowTurnoverCutoff, rated.size()));

        List<TurnoverRatedCandidate> targets = lowTurnoverGroup.stream()
                .limit(TARGET_COUNT)
                .toList();

        for (int rank = 0; rank < targets.size(); rank++) {
            TurnoverRatedCandidate target = targets.get(rank);
            campaignTargetLotRepository.save(CampaignTargetLot.register(
                    today,
                    target.candidate().stockLotId(),
                    target.turnoverRate(),
                    target.candidate().availableQty(),
                    rank + 1));
        }
    }

    // 소진율까지 계산해 붙인 후보. 정렬/컷오프 계산에만 쓰는 배치 내부 전용 값이다
    private record TurnoverRatedCandidate(CampaignTargetLotCandidate candidate, BigDecimal turnoverRate) {
    }
}
