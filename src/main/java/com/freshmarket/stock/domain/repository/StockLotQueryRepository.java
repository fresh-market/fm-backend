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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/*
 * QueryDSL 로 짜는 로트 동적 조회 전용 컴포넌트. ProductQueryRepository 와 같은 이유로
 * Spring Data 의 Custom+Impl 관례 대신 일반 빈으로 둔다 (DPB-4-10).
 *
 * findAvailableLots()는 정렬 축이 productOptionId 하나뿐이라 목록 조회(ProductQueryRepository)처럼
 * "정렬 축과 커서 축 불일치" 문제가 생기지 않아 단순 오름차순 + 커서 비교면 충분하다.
 * findByProductOptionIds()는 정렬이 expiryDate(FEFO)라 축이 갈려, id를 동점 처리 키로 함께 쓴다.
 * 옵션이 MAX_OPTIONS_FOR_PER_OPTION_QUERY 이하면 단일 쿼리로 안 묶고 옵션별로 나눠 물은 뒤
 * K-way 병합한다 — 이유는 그 메서드 주석 참고. 넘으면 쿼리 개수 상한(PERF-2-01) 때문에
 * IN(...) 한 방 쿼리로 되돌아간다.
 */
@Repository
@RequiredArgsConstructor
public class StockLotQueryRepository {

    /*
     * (PERF-2-01) 요청당 쿼리 수 10개 이하가 팀 기준이다. 옵션별로 쿼리를 나누면 옵션 수만큼
     * 쿼리가 늘어나므로, 이 상한을 넘는 상품은 옵션별 병합을 포기하고 IN(...) 한 방 쿼리로
     * 되돌아간다 — filesort를 감수하더라도 쿼리 개수 상한을 넘기지 않는 쪽을 택한다. 지금
     * 도메인에서 상품 하나가 옵션을 이만큼 가질 일은 없지만(1kg/500g/200g 수준), 방어적으로 둔다.
     * findAllByProduct()가 이 조회 앞에 옵션 ID 목록 조회를 한 번 더 하므로, 여유를 두고
     * 8로 잡는다(8 + 옵션 목록 조회 1 = 9, 상한 10 이내).
     */
    static final int MAX_OPTIONS_FOR_PER_OPTION_QUERY = 8;

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
     * (API-3-04, PERF-2-03) 상품의 옵션 ID 목록에 속한 로트를 소비기한 오름차순(FEFO)으로
     * pageSize + 1건 조회한다.
     *
     * productOptionId를 IN(...)으로 한 방에 물으면 옵션이 2개 이상일 때 idx_lot_fefo
     * (product_option_id, status, expiry_date)가 "옵션별로 그룹핑"과 "옵션을 넘나드는 전역
     * expiry_date 정렬"을 동시에 만족 못 해, EXPLAIN ANALYZE로 실측한 결과 filesort가 붙거나
     * (45,000행 기준 옵션 5개: 7ms) 옵티마이저가 아예 인덱스를 포기하고 풀 테이블 스캔을 택했다
     * (5,000행 기준: 2.4ms, 옵션 1개 대비 18배).
     *
     * 대신 옵션마다 따로 물으면(findByProductOptionId) product_option_id가 단일 값이 되어
     * idx_lot_fefo를 인덱스 seek 하나로 온전히 탄다(같은 실측에서 0.137ms, filesort 없음) —
     * 옵션별로 이미 정렬된 결과가 나오므로, 그 N개를 애플리케이션에서 K-way 병합해 전역
     * expiry_date 순서를 만든다. 상품당 옵션 수가 적어(보통 몇 개) 쿼리 N번의 비용 합이
     * IN(...) 한 방의 filesort/풀스캔보다 훨씬 싸다.
     */
    public List<StockLot> findByProductOptionIds(List<Long> productOptionIds, boolean availableOnly,
            PageCursor cursor, int pageSize) {
        int limit = pageSize + 1;
        if (productOptionIds.size() > MAX_OPTIONS_FOR_PER_OPTION_QUERY) {
            return findByProductOptionIdsInOneQuery(productOptionIds, availableOnly, cursor, limit);
        }
        List<List<StockLot>> perOption = productOptionIds.stream()
                .map(optionId -> findByProductOptionId(optionId, availableOnly, cursor, limit))
                .toList();
        return mergeSortedByExpiry(perOption, limit);
    }

    // 옵션 하나에 대해 idx_lot_fefo를 seek 하나로 타는 조회. product_option_id가 단일 값이라
    // 결과가 이미 expiry_date, id 순으로 정렬돼 나온다
    private List<StockLot> findByProductOptionId(Long productOptionId, boolean availableOnly, PageCursor cursor,
            int limit) {
        return queryFactory
                .selectFrom(stockLot)
                .where(
                        stockLot.productOptionId.eq(productOptionId),
                        availableOnlyFilter(availableOnly),
                        expiryDateCursorAfter(cursor))
                .orderBy(stockLot.expiryDate.asc(), stockLot.id.asc())
                .limit(limit)
                .fetch();
    }

    // MAX_OPTIONS_FOR_PER_OPTION_QUERY를 넘는 옵션 수일 때의 대체 경로. 쿼리 1개로 끝나지만
    // idx_lot_fefo가 전역 expiry_date 정렬을 못 줘 filesort가 붙는다(클래스 주석 참고)
    private List<StockLot> findByProductOptionIdsInOneQuery(List<Long> productOptionIds, boolean availableOnly,
            PageCursor cursor, int limit) {
        return queryFactory
                .selectFrom(stockLot)
                .where(
                        stockLot.productOptionId.in(productOptionIds),
                        availableOnlyFilter(availableOnly),
                        expiryDateCursorAfter(cursor))
                .orderBy(stockLot.expiryDate.asc(), stockLot.id.asc())
                .limit(limit)
                .fetch();
    }

    /*
     * 이미 (expiryDate, id) 순으로 정렬된 리스트 여러 개를 K-way 병합해 상위 limit건만 뽑는다.
     * 각 리스트가 최대 limit건이라, 전역 상위 limit건은 어느 한 리스트에서도 limit건을 넘게
     * 가져올 필요가 없다 — 그래서 findByProductOptionId가 limit건씩만 가져와도 정확하다.
     * DB 없이 순수 로직으로 검증 가능하도록 패키지 전용으로 둔다(테스트에서 직접 호출).
     */
    List<StockLot> mergeSortedByExpiry(List<List<StockLot>> sortedLists, int limit) {
        Comparator<int[]> byCurrentElement = Comparator.comparing(
                (int[] cursor) -> sortedLists.get(cursor[0]).get(cursor[1]),
                Comparator.comparing(StockLot::getExpiryDate).thenComparing(StockLot::getId));
        PriorityQueue<int[]> heap = new PriorityQueue<>(byCurrentElement);
        for (int i = 0; i < sortedLists.size(); i++) {
            if (!sortedLists.get(i).isEmpty()) {
                heap.offer(new int[] {i, 0});
            }
        }

        List<StockLot> merged = new ArrayList<>(limit);
        while (!heap.isEmpty() && merged.size() < limit) {
            int[] cursor = heap.poll();
            List<StockLot> list = sortedLists.get(cursor[0]);
            merged.add(list.get(cursor[1]));
            if (cursor[1] + 1 < list.size()) {
                heap.offer(new int[] {cursor[0], cursor[1] + 1});
            }
        }
        return merged;
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