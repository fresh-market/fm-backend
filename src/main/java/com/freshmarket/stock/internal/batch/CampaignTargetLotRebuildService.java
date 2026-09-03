package com.freshmarket.stock.internal.batch;

import com.freshmarket.stock.internal.ExpiringSoonPolicy;
import com.freshmarket.stock.internal.TurnoverRateCalculator;
import com.freshmarket.stock.internal.dto.CampaignTargetLotCandidate;
import com.freshmarket.stock.internal.exception.StockErrorCode;
import com.freshmarket.stock.internal.exception.StockException;
import com.freshmarket.stock.internal.dto.LotDisposedQty;
import com.freshmarket.stock.internal.entity.CampaignTargetLot;
import com.freshmarket.stock.internal.repository.CampaignTargetLotRepository;
import com.freshmarket.stock.internal.repository.StockLotQueryRepository;
import com.freshmarket.stock.internal.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/*
 * 선착순 쿠폰 캠페인 대상 로트를 확정한다. 실행 시점은 부르는 쪽이 정한다 —
 * 자정 스케줄(CampaignTargetLotBatch)과 관리자 재실행 API 가 이것을 함께 쓴다.
 *
 * 조건: 소비기한 임박(D-13 ~ D-10) + 잔여재고(availableQty) 30 이상인 후보 중
 * 소진율 오름차순 하위 10% 전체. 건수 상한은 두지 않으므로 후보가 늘면 대상도 함께 는다.
 *
 * 소비기한 구간의 하한이 판매 마감 기한(D-10)인 것이 핵심이다. 그보다 소비기한이 가까운
 * 로트는 이미 팔 수 없어서 쿠폰을 붙여도 쓸 수가 없다. 회원용 소비기한 임박 조회
 * (ExpiringSoonService)가 보는 구간과 같아야 "대상과 노출이 어긋나지 않는다".
 *
 * 이 클래스에는 @Profile 을 걸지 않는다. 스케줄은 배치 인스턴스에서만 돌지만 관리자
 * 재실행은 API 인스턴스에서 들어오므로 양쪽에 다 있어야 한다. batch 프로필로 묶이는 것은
 * @Scheduled 를 가진 CampaignTargetLotBatch 쪽이다 (INF-1-10, ArchitectureTest 로 강제됨).
 *
 * 배치 분류: 재계산형(같은 입력이면 같은 결과, 재실행 시 당일분을 지우고 다시 계산한다).
 * 그래서 관리자가 여러 번 눌러도 결과가 같다.
 *
 * 동시 실행은 막지 않는다. 자정 스케줄은 배치 인스턴스 한 대에서만 돌고, 관리자 재실행이
 * 겹치면 uk_campaign_target_date_lot 이 뒤엣것을 거절해 데이터가 섞이지 않는다.
 * 인스턴스가 늘거나 재실행이 잦아지면 그때 ShedLock 등 분산 실행 제어를 도입한다.
 */
@Component
@RequiredArgsConstructor
public class CampaignTargetLotRebuildService {

    /*
     * 판매 마감 기한 = 소비기한 - 10일, 그 앞 3일이 임박 구간이다(팀 통일 규칙).
     * Product.saleAvailableDaysFromExpiry 가 전 상품 10 으로 통일된 것에 기대는 값이라,
     * 카테고리별로 값이 갈리면 여기도 함께 바뀌어야 한다.
     *
     * long 으로 두는 이유는 LocalDate.plusDays(long) 에 그대로 넘기기 때문이다.
     * int 로 두면 둘을 더하는 시점에 int 덧셈이 일어난 뒤 long 으로 넓혀져
     * 오버플로 가능성을 지적받는다 (S2184).
     */
    private static final long SALE_CLOSE_DAYS = 10;
    private static final long EXPIRING_SOON_DAYS = 3;
    private static final int MIN_AVAILABLE_QTY = 30;
    // 하위 10% = 1/10. double 0.1 곱셈은 부동소수점 오차로 ceil() 결과가 하나 더 잘릴 수 있어
    // 정수 나눗셈으로 대신한다 (EJ-8-04)
    private static final int LOW_TURNOVER_PERCENTILE_DIVISOR = 10;

    private final StockLotQueryRepository stockLotQueryRepository;
    private final CampaignTargetLotRepository campaignTargetLotRepository;
    private final StockMovementRepository stockMovementRepository;
    private final Clock clock;

    // 이 인스턴스에서 확정이 돌고 있는지. 겹친 호출을 걸러낸다
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    /*
     * 오늘자 대상을 다시 확정하고 확정된 건수를 돌려준다.
     *
     * 겹쳐 들어오면 뒤엣것을 막는다. 확정은 그날 행을 지우고 다시 넣는 작업이라 겹치면
     * uk_campaign_target_date_lot 이 거절하는데, 그 전에 여기서 걸러 무슨 상황인지 알린다.
     *
     * 이 플래그는 이 JVM 안에서만 유효하다. 관리자가 버튼을 두 번 누르는 가장 흔한 경우를
     * 막는 것이 목적이고, 인스턴스를 넘는 동시 실행은 유니크 제약이 마지막으로 막는다.
     * 분산 실행 제어(ShedLock 등)는 인스턴스가 여러 대로 늘어날 때 함께 도입한다.
     *
     * 플래그를 트랜잭션 안에서 내린다. finally 가 커밋보다 먼저 도는 짧은 창이 남지만,
     * 그 사이에 들어온 것은 유니크 제약이 받아낸다. 창을 없애려고 빈을 둘로 나누는 것보다
     * 이쪽이 읽기 쉽다고 봤다.
     */
    @Transactional
    public int rebuild() {
        if (!rebuilding.compareAndSet(false, true)) {
            throw new StockException(StockErrorCode.CAMPAIGN_REBUILD_IN_PROGRESS);
        }
        try {
            return confirmToday();
        } finally {
            rebuilding.set(false);
        }
    }

    private int confirmToday() {
        LocalDate today = ExpiringSoonPolicy.businessToday(clock);

        campaignTargetLotRepository.deleteByTargetDate(today); // 재실행 시 동일 일자 집계는 덮어쓴다

        /*
         * 판매 마감 기한(today+10) 부터 임박 시작선(today+13) 까지.
         * 하한이 있어야 팔 수 있는 로트만 남는다.
         *
         * 확보 재고와 잔여재고 하한도 함께 넘겨 SQL 에서 거른다 — 행 하나만 보고 판정되는
         * 조건이라 자바로 가져와 버릴 이유가 없다.
         */
        List<CampaignTargetLotCandidate> candidates = stockLotQueryRepository.findCandidatesExpiringBetween(
                today.plusDays(SALE_CLOSE_DAYS),
                today.plusDays(SALE_CLOSE_DAYS + EXPIRING_SOON_DAYS),
                MIN_AVAILABLE_QTY);
        if (candidates.isEmpty()) {
            return 0;
        }

        Map<Long, Long> disposedByLotId = findDisposedQtyByLotId(candidates);

        List<TurnoverRatedCandidate> rated = candidates.stream()
                .map(c -> new TurnoverRatedCandidate(c, turnoverRateOf(c, disposedByLotId)))
                .sorted(Comparator.comparing(TurnoverRatedCandidate::turnoverRate))
                .toList();

        // ceil(n / 10) 를 정수 나눗셈으로 계산한다: (n + divisor - 1) / divisor
        int lowTurnoverCutoff = (rated.size() + LOW_TURNOVER_PERCENTILE_DIVISOR - 1) / LOW_TURNOVER_PERCENTILE_DIVISOR;
        List<TurnoverRatedCandidate> targets = rated.subList(
                0, Math.min(lowTurnoverCutoff, rated.size()));

        for (int rank = 0; rank < targets.size(); rank++) {
            TurnoverRatedCandidate target = targets.get(rank);
            campaignTargetLotRepository.save(CampaignTargetLot.register(
                    today,
                    target.candidate().stockLotId(),
                    target.turnoverRate(),
                    target.candidate().availableQty(),
                    rank + 1));
        }

        return targets.size();
    }

    // 후보 로트들의 폐기 누계를 한 번에 조회한다. 폐기 이력이 없는 로트는 결과에 없으므로 0 으로 본다
    private Map<Long, Long> findDisposedQtyByLotId(List<CampaignTargetLotCandidate> candidates) {
        List<Long> stockLotIds = candidates.stream()
                .map(CampaignTargetLotCandidate::stockLotId)
                .toList();
        return stockMovementRepository.findDisposedQtyByStockLotIds(stockLotIds).stream()
                .collect(Collectors.toMap(LotDisposedQty::stockLotId, LotDisposedQty::disposedQty));
    }

    /*
     * 폐기분을 걷어낸 소진율.
     *
     * 폐기는 availableQty 를 줄이지만 팔린 것이 아니다. 그대로 두면 일부만 폐기된 로트가
     * "그만큼 팔린" 것으로 보여 소진율이 부풀고, 정작 안 팔리는 재고가 대상에서 빠진다.
     *
     * 분모를 "팔 수 있었던 수량"(입고 - 폐기)으로 바꾸면
     *   (입고 - 잔여 - 폐기) / (입고 - 폐기)
     * 가 그대로 나와, 계산기는 손대지 않고 폐기만 걷어낼 수 있다.
     *
     * 분모는 항상 잔여재고 이상이고(잔여 = 입고 - 예약 - 폐기), 잔여재고 30 이상을 이미
     * 걸렀으므로 0 으로 나눌 일은 없다.
     */
    private BigDecimal turnoverRateOf(CampaignTargetLotCandidate candidate, Map<Long, Long> disposedByLotId) {
        long disposedQty = disposedByLotId.getOrDefault(candidate.stockLotId(), 0L);
        int sellableQty = candidate.initialQty() - (int) disposedQty;
        return TurnoverRateCalculator.calculate(sellableQty, candidate.availableQty());
    }

    // 소진율까지 계산해 붙인 후보. 정렬/컷오프 계산에만 쓰는 배치 내부 전용 값이다
    private record TurnoverRatedCandidate(CampaignTargetLotCandidate candidate, BigDecimal turnoverRate) {
    }
}
