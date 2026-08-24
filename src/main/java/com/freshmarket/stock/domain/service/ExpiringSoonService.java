package com.freshmarket.stock.domain.service;

import static com.freshmarket.stock.domain.ExpiringSoonPolicy.DEFAULT_PAGE_SIZE;
import static com.freshmarket.stock.domain.ExpiringSoonPolicy.DEFAULT_WITHIN_DAYS;

import static com.freshmarket.stock.domain.ExpiringSoonPolicy.DEFAULT_PAGE_SIZE;
import static com.freshmarket.stock.domain.ExpiringSoonPolicy.DEFAULT_WITHIN_DAYS;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.domain.ExpiringSoonJudge;
import com.freshmarket.stock.domain.dto.ExpiringSoonResponse;
import com.freshmarket.stock.domain.dto.StockLotView;
import com.freshmarket.stock.domain.repository.StockLotQueryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 회원에게 소비기한 임박 상품을 노출한다.
 *
 * 판정 기준: 소비기한 - sale_available_days_from_expiry(판매 마감 기한) 까지
 * 남은 일수가 withinDays 이내면 임박으로 본다. 실제 계산은 ExpiringSoonJudge 가 한다.
 *
 * 정렬은 productOptionId 오름차순 커서 페이지네이션이다. 임박한 순으로 보여주면
 * 첫 페이지만 계속 노출되어 특정 상품에 판매가 쏠린다 — 순환 노출을 위해 정렬 축을
 * 임박도가 아니라 안정적인 식별자로 둔다.
 *
 * purchasable/categoryId 필터링이 DB 조회 이후 자바에서 일어나, 정확히 pageSize
 * 만큼 응답하지 못할 수 있다. FETCH_MULTIPLIER 만큼 넉넉히 가져와 이 문제를 줄인다.
 * 완벽한 보장은 아니지만(가능성은 낮음), 반복 조회 없이 단순하게 개선한다.
 *
 * stock(L1) 은 product(L0) 의 엔티티/Q클래스를 직접 참조할 수 없다
 * (ArchitectureTest: "slices should not depend on each other"). product 정보는
 * 전부 ProductApi 를 거쳐서만 가져온다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpiringSoonService {

    // purchasable/categoryId 필터링으로 걸러질 것을 감안해 여유 있게 가져온다
    private static final int FETCH_MULTIPLIER = 2;

    private final StockLotQueryRepository stockLotQueryRepository;
    private final ProductApi productApi;
    private final Clock clock;

    public CursorPageResponse<ExpiringSoonResponse> getExpiringSoonProducts(
            int withinDays, Long categoryId, Long cursorProductOptionId, int pageSize) {

        int effectiveWithinDays = withinDays > 0 ? withinDays : DEFAULT_WITHIN_DAYS;
        int effectivePageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        LocalDate judgmentStart = LocalDate.now(clock).plusDays(effectiveWithinDays);

        int fetchSize = effectivePageSize * FETCH_MULTIPLIER;
        List<StockLotView> lots = stockLotQueryRepository.findAvailableLots(
                cursorProductOptionId, fetchSize);

        boolean hasMoreInBatch = lots.size() > fetchSize;
        List<StockLotView> batch = hasMoreInBatch ? lots.subList(0, fetchSize) : lots;

        if (batch.isEmpty()) {
            return CursorPageResponse.of(List.of(), null);
        }

        List<Long> productOptionIds = batch.stream()
                .map(StockLotView::productOptionId)
                .distinct()
                .toList();
        List<ProductOptionInfo> infos = productApi.findOptionInfos(productOptionIds);
        Map<Long, ProductOptionInfo> infoByOptionId = infos.stream()
                .collect(Collectors.toMap(ProductOptionInfo::productOptionId, Function.identity()));

        List<Map.Entry<StockLotView, ProductOptionInfo>> filtered = batch.stream()
                .filter(lot -> infoByOptionId.containsKey(lot.productOptionId()))
                .map(lot -> Map.entry(lot, infoByOptionId.get(lot.productOptionId())))
                .filter(e -> e.getValue().purchasable())
                .filter(e -> categoryId == null || categoryId.equals(e.getValue().categoryId()))
                .filter(e -> ExpiringSoonJudge.isExpiringSoon(
                        e.getKey(), e.getValue().saleAvailableDaysFromExpiry(), judgmentStart))
                .toList();

        // 필터링 결과가 pageSize 보다 많으면 다음 페이지가 있다고 본다
        boolean hasNext = filtered.size() > effectivePageSize || hasMoreInBatch;
        List<Map.Entry<StockLotView, ProductOptionInfo>> page = filtered.size() > effectivePageSize
                ? filtered.subList(0, effectivePageSize)
                : filtered;

        List<ExpiringSoonResponse> items = page.stream()
                .map(e -> ExpiringSoonResponse.from(e.getValue()))
                .distinct()
                .toList();

        String nextToken = hasNext && !page.isEmpty()
                ? String.valueOf(page.get(page.size() - 1).getKey().productOptionId())
                : null;

        return CursorPageResponse.of(items, nextToken);
    }
}