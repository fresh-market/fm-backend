package com.freshmarket.stock.domain.repository;

import static com.freshmarket.stock.domain.entity.QStockLot.stockLot;

import com.freshmarket.common.query.CollationExpressions;
import com.freshmarket.stock.domain.dto.StockLotView;
import com.freshmarket.stock.domain.entity.LotStatus;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/*
 * QueryDSL 로 짜는 로트 동적 조회 전용 컴포넌트. ProductQueryRepository 와 같은 이유로
 * Spring Data 의 Custom+Impl 관례 대신 일반 빈으로 둔다 (DPB-4-10).
 *
 * 정렬 축이 productOptionId 하나뿐이라 목록 조회(ProductQueryRepository)처럼
 * "정렬 축과 커서 축 불일치" 문제가 생기지 않는다. 단순 오름차순 + 커서 비교면 충분하다.
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
}
