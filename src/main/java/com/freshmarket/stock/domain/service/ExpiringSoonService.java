package com.freshmarket.stock.domain.service;

import static com.freshmarket.stock.domain.ExpiringSoonPolicy.DEFAULT_PAGE_SIZE;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.domain.dto.ExpiringSoonResponse;
import com.freshmarket.stock.domain.entity.CampaignTargetLot;
import com.freshmarket.stock.domain.entity.StockLot;
import com.freshmarket.stock.domain.repository.CampaignTargetLotRepository;
import com.freshmarket.stock.domain.repository.StockLotRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/*
 * 회원에게 소비기한 임박 떨이 쿠폰 대상 상품을 노출한다.
 *
 * 임박 여부를 여기서 계산하지 않는다 — 자정 배치(CampaignTargetLotBatch)가 확정해 둔
 * campaign_target_lot 을 그대로 읽는다. 관리자용 조회(AdminCampaignTargetLotService)와
 * 같은 표를 보므로 "캠페인 대상과 회원에게 노출되는 상품이 어긋나는" 문제가 구조적으로 없다.
 * 요청 시점마다 다시 계산하지 않으니 같은 기준일에는 항상 같은 목록이 나온다.
 *
 * 정렬은 targetRank(소진율 오름차순) 이다. 임박도 순 노출은 첫 페이지에 판매가 쏠린다는
 * 우려가 있었지만, 대상이 하위 10% 로 좁혀져 목록 자체가 짧으므로 관리자 조회와 같은
 * 순서를 쓰는 편이 낫다고 봤다.
 *
 * 트랜잭션을 걸지 않는다 — 중간에 ProductApi 외부 호출이 끼어 있어, 감싸면 그 호출이 읽기
 * 트랜잭션 안에 들어간다 (DI-4-02, AdminCampaignTargetLotService 와 같은 이유).
 *
 * stock(L1) 은 product(L0) 의 엔티티를 직접 참조할 수 없다
 * (ArchitectureTest: "slices should not depend on each other"). 상품 정보는 ProductApi 로만 가져온다.
 */
@Service
@RequiredArgsConstructor
public class ExpiringSoonService {

    // categoryId/purchasable 필터로 걸러질 것을 감안해 여유 있게 가져온다
    private static final int FETCH_MULTIPLIER = 2;

    private final CampaignTargetLotRepository campaignTargetLotRepository;
    private final StockLotRepository stockLotRepository;
    private final ProductApi productApi;
    private final Clock clock;

    public CursorPageResponse<ExpiringSoonResponse> getExpiringSoonProducts(
            Long categoryId, String pageToken, int pageSize) {

        int effectivePageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        LocalDate today = LocalDate.now(clock);

        PageCursor cursor = PageTokens.decode(pageToken);
        int afterRank = cursor != null ? cursor.id().intValue() : 0;

        int fetchSize = effectivePageSize * FETCH_MULTIPLIER;
        List<CampaignTargetLot> targetLots = campaignTargetLotRepository
                .findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                        today, afterRank, PageRequest.of(0, fetchSize + 1));

        boolean hasMoreInBatch = targetLots.size() > fetchSize;
        List<CampaignTargetLot> batch = hasMoreInBatch ? targetLots.subList(0, fetchSize) : targetLots;

        if (batch.isEmpty()) {
            return CursorPageResponse.of(List.of(), null);
        }

        Map<Long, ProductOptionInfo> infoByStockLotId = findProductInfoByStockLotId(batch);

        List<Map.Entry<CampaignTargetLot, ProductOptionInfo>> filtered = batch.stream()
                .filter(lot -> infoByStockLotId.containsKey(lot.getStockLotId()))
                .map(lot -> Map.entry(lot, infoByStockLotId.get(lot.getStockLotId())))
                .filter(e -> e.getValue().purchasable())
                .filter(e -> categoryId == null || categoryId.equals(e.getValue().categoryId()))
                .toList();

        boolean hasNext = filtered.size() > effectivePageSize || hasMoreInBatch;
        List<Map.Entry<CampaignTargetLot, ProductOptionInfo>> page = filtered.size() > effectivePageSize
                ? filtered.subList(0, effectivePageSize)
                : filtered;

        List<ExpiringSoonResponse> items = page.stream()
                .map(e -> ExpiringSoonResponse.from(e.getValue()))
                .distinct()
                .toList();

        String nextToken = hasNext && !page.isEmpty()
                ? PageTokens.encode(new PageCursor(
                        (long) page.get(page.size() - 1).getKey().getTargetRank(), null))
                : null;

        return CursorPageResponse.of(items, nextToken);
    }

    /*
     * 대상 로트마다 상품 정보를 붙인다. CampaignTargetLot 은 stockLotId 만 갖고 있어
     * StockLot 을 한 번 더 조회해 productOptionId 를 얻고, 그것으로 ProductApi 를 부른다.
     * 두 단계 모두 IN 조회 한 번씩이라 대상 수와 무관하게 쿼리는 두 번이다.
     */
    private Map<Long, ProductOptionInfo> findProductInfoByStockLotId(List<CampaignTargetLot> targetLots) {
        List<Long> stockLotIds = targetLots.stream()
                .map(CampaignTargetLot::getStockLotId)
                .toList();
        List<StockLot> stockLots = stockLotRepository.findAllById(stockLotIds);

        List<Long> productOptionIds = stockLots.stream()
                .map(StockLot::getProductOptionId)
                .distinct()
                .toList();
        Map<Long, ProductOptionInfo> infoByOptionId = productApi.findOptionInfos(productOptionIds).stream()
                .collect(Collectors.toMap(ProductOptionInfo::productOptionId, Function.identity()));

        return stockLots.stream()
                .filter(lot -> infoByOptionId.containsKey(lot.getProductOptionId()))
                .collect(Collectors.toMap(
                        StockLot::getId, lot -> infoByOptionId.get(lot.getProductOptionId())));
    }
}
