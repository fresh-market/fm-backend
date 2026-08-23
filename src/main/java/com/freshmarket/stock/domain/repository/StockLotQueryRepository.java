package com.freshmarket.stock.domain.repository;

import static com.freshmarket.stock.domain.entity.QStockLot.stockLot;

import com.freshmarket.common.query.CollationExpressions;
import com.freshmarket.stock.domain.dto.StockLotView;
import com.freshmarket.stock.domain.entity.LotStatus;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
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

    // 판매 가능(AVAILABLE) 상태의 로트를 전부 조회한다. 소비기한 임박 판정용
    public List<StockLotView> findAvailableLots() {
        return queryFactory
                .select(Projections.constructor(StockLotView.class,
                        stockLot.productOptionId, stockLot.expiryDate))
                .from(stockLot)
                .where(CollationExpressions.equalsAsCs(stockLot.status, LotStatus.AVAILABLE))
                .fetch();
    }
}
