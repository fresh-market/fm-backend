package com.freshmarket.stock.domain.repository;

import static com.freshmarket.stock.domain.entity.QStockLot.stockLot;

import com.freshmarket.common.query.CollationExpressions;
import com.freshmarket.stock.domain.dto.CampaignTargetLotCandidate;
import com.freshmarket.stock.domain.dto.QCampaignTargetLotCandidate;
import com.freshmarket.stock.domain.dto.StockLotView;
import com.freshmarket.stock.domain.entity.LotStatus;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/*
 * QueryDSL 로 짜는 로트 동적 조회 전용 컴포넌트. ProductQueryRepository 와 같은 이유로
 * Spring Data 의 Custom+Impl 관례 대신 일반 빈으로 둔다 (DPB-4-10).
 */
@Repository
@RequiredArgsConstructor
public class StockLotQueryRepository {

    private final JPAQueryFactory queryFactory;

    // 판매 가능(AVAILABLE) 상태의 로트를, productOptionId 오름차순으로 pageSize + 1건 조회한다
    public List<StockLotView> findAvailableLots(Long cursorProductOptionId, int pageSize) {
        return queryFactory
                .select(Projections.constructor(StockLotView.class,
                        stockLot.productOptionId, stockLot.expiryDate))
                .from(stockLot)
                .where(
                        CollationExpressions.equalsAsCs(stockLot.status, LotStatus.AVAILABLE),
                        cursorAfter(cursorProductOptionId))
                .orderBy(stockLot.productOptionId.asc())
                .limit(pageSize + 1L)
                .fetch();
    }

    private BooleanExpression cursorAfter(Long cursorProductOptionId) {
        return cursorProductOptionId != null
                ? stockLot.productOptionId.gt(cursorProductOptionId)
                : null;
    }

    /*
     * 캠페인 대상 상품 조회 배치용. 소비기한이 withinDate 이내인 AVAILABLE 로트 전체를
     * 가져온다. 소진율 계산과 재고 하한 필터는 자바에서 한다 — 이유는 두 가지다.
     * 1) 소진율(initialQty, availableQty 기반 계산)을 SQL 에서 하려면 QueryDSL 이
     *    지원하지 않는 산술 표현이 얽혀 위험하다(날짜 뺄셈 미지원 사례와 같은 종류).
     * 2) "소진율 하위 20%" 는 전체 대상 안에서의 상대적 순위라 애초에 SQL 한 줄로
     *    표현하기 어렵다.
     */
    public List<CampaignTargetLotCandidate> findCandidatesExpiringBy(LocalDate withinDate) {
        return queryFactory
                .select(new QCampaignTargetLotCandidate(
                        stockLot.id,
                        stockLot.productOptionId,
                        stockLot.expiryDate,
                        stockLot.initialQty,
                        stockLot.availableQty))
                .from(stockLot)
                .where(
                        CollationExpressions.equalsAsCs(stockLot.status, LotStatus.AVAILABLE),
                        stockLot.expiryDate.loe(withinDate))
                .fetch();
    }
}
