package com.freshmarket.stock.domain.repository;

import static com.freshmarket.stock.domain.entity.QStockLot.stockLot;

import com.freshmarket.common.query.CollationExpressions;
import com.freshmarket.common.response.PageCursor;
import com.freshmarket.stock.domain.dto.CampaignTargetLotCandidate;
import com.freshmarket.stock.domain.dto.QCampaignTargetLotCandidate;
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
     * 캠페인 대상 로트 배치용. 소비기한이 [from, to] 구간에 드는 AVAILABLE 로트를 가져온다.
     *
     * 구간의 양 끝이 모두 필요하다. to(=today+13) 는 "소비기한 임박" 시작선이고,
     * from(=today+10) 은 "판매 마감 기한" 선이다. 하한이 없으면 판매 마감이 지나 이미 팔 수
     * 없는 로트까지 후보에 들어온다 — 그런 로트에 쿠폰을 붙여봐야 쓸 수가 없다.
     * 만료 배치는 소비기한이 지난 뒤에야 걷어가므로 그 사이 로트가 계속 AVAILABLE 로 쌓인다.
     *
     * 소비기한·재고 하한처럼 행 하나만 보고 판정되는 조건은 전부 SQL 에서 거른다.
     * 자바로 가져와 거르면 버릴 행까지 전송하고 객체로 만들게 된다.
     *
     * 반면 소진율 계산과 순위 컷은 자바에 남긴다 — 이유는 두 가지다.
     * 1) 소진율(initialQty, availableQty, 폐기 누계 기반 계산)을 SQL 에서 하려면 QueryDSL 이
     *    지원하지 않는 산술 표현이 얽혀 위험하다(날짜 뺄셈 미지원 사례와 같은 종류).
     * 2) "소진율 하위 10%" 는 전체 대상 안에서의 상대적 순위라 애초에 SQL 한 줄로
     *    표현하기 어렵다.
     *
     * expiry_date 범위가 idx_lot_expiry_date 를 탄다 (V19). status 는 CollationExpressions 가
     * 컬럼을 collate() 로 감싸 인덱스를 못 타므로, 인덱스 선두를 expiry_date 로 잡았다.
     */
    public List<CampaignTargetLotCandidate> findCandidatesExpiringBetween(
            LocalDate from, LocalDate to, int minAvailableQty) {
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
                        stockLot.expiryDate.goe(from),
                        stockLot.expiryDate.loe(to),
                        stockLot.initialQty.gt(0),
                        stockLot.availableQty.goe(minAvailableQty))
                .fetch();
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
