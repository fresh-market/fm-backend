package com.freshmarket.stock.domain.repository;

import static com.freshmarket.stock.domain.entity.QStockLot.stockLot;

import com.freshmarket.common.query.CollationExpressions;
import com.freshmarket.common.response.PageCursor;
import com.freshmarket.stock.domain.dto.StockLotView;
import com.freshmarket.stock.domain.entity.LotStatus;
import com.freshmarket.stock.domain.entity.StockLot;
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
 *
 * findAvailableLots()는 정렬 축이 productOptionId 하나뿐이라 목록 조회(ProductQueryRepository)처럼
 * "정렬 축과 커서 축 불일치" 문제가 생기지 않아 단순 오름차순 + 커서 비교면 충분하다.
 * findByProductOptionIds()는 정렬이 expiryDate(FEFO)라 축이 갈려, id를 동점 처리 키로 함께 쓴다.
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
     * (API-3-04) 상품의 옵션 ID 목록에 속한 로트를 소비기한 오름차순(FEFO)으로 pageSize + 1건
     * 조회한다. 정렬 축(expiryDate)과 커서 동점 처리 축(id)이 ProductQueryRepository의 가격
     * 정렬과 같은 구조라, 같은 방식(정렬값 > 커서 OR 정렬값 = 커서 AND id > 커서id)을 쓴다.
     */
    public List<StockLot> findByProductOptionIds(List<Long> productOptionIds, boolean availableOnly,
            PageCursor cursor, int pageSize) {
        return queryFactory
                .selectFrom(stockLot)
                .where(
                        stockLot.productOptionId.in(productOptionIds),
                        availableOnlyFilter(availableOnly),
                        expiryDateCursorAfter(cursor))
                .orderBy(stockLot.expiryDate.asc(), stockLot.id.asc())
                .limit(pageSize + 1L)
                .fetch();
    }

    private BooleanExpression availableOnlyFilter(boolean availableOnly) {
        return availableOnly ? CollationExpressions.equalsAsCs(stockLot.status, LotStatus.AVAILABLE) : null;
    }

    // 오름차순 정렬이라 "다음 페이지"는 커서보다 크거나, 같으면 id가 더 큰 행이다
    private BooleanExpression expiryDateCursorAfter(PageCursor cursor) {
        if (cursor == null) {
            return null;
        }
        LocalDate cursorExpiryDate = LocalDate.parse(cursor.sortValue());
        Long cursorId = cursor.id();
        return stockLot.expiryDate.gt(cursorExpiryDate)
                .or(stockLot.expiryDate.eq(cursorExpiryDate).and(stockLot.id.gt(cursorId)));
    }
}