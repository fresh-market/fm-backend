package com.freshmarket.stock.internal.service;

import static com.freshmarket.stock.internal.ExpiringSoonPolicy.DEFAULT_PAGE_SIZE;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.internal.ExpiringSoonPolicy;
import com.freshmarket.stock.internal.dto.ExpiringSoonResponse;
import com.freshmarket.stock.internal.entity.CampaignTargetLot;
import com.freshmarket.stock.internal.repository.CampaignTargetLotCacheRepository;
import com.freshmarket.stock.internal.repository.CampaignTargetLotRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final CampaignTargetLotProductInfoService campaignTargetLotProductInfoService;
    private final CampaignTargetLotCacheRepository campaignTargetLotCacheRepository;
    private final Clock clock;

    /*
     * 확정본이라 하루 종일 값이 같아 캐시를 앞에 둔다. 캐시에 없으면 그대로 DB 에서 구하고
     * 그 결과를 채워둔다 — 캐시는 없어도 동작에 지장이 없는 층이다.
     *
     * 캐시 키에 확정본 버전을 함께 넣는다. 관리자가 재실행하면 그날 행이 새로 만들어져
     * 버전이 바뀌고, 키가 달라져 옛 응답을 다시 내보내지 않는다. 로컬 캐시라 인스턴스별로
     * 비울 방법이 없어 무효화 대신 키를 가르는 쪽을 택했다.
     *
     * 버전 조회가 요청마다 한 번 는다. 인덱스만 읽는 집계라, 캐시가 없을 때 치던
     * 3~4 쿼리와 ProductApi 호출에 비하면 값이 싸다.
     */
    public CursorPageResponse<ExpiringSoonResponse> getExpiringSoonProducts(
            Long categoryId, String pageToken, int pageSize) {

        int effectivePageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        LocalDate today = ExpiringSoonPolicy.businessToday(clock);
        Long version = campaignTargetLotRepository.findConfirmedVersion(today);

        Optional<CursorPageResponse<ExpiringSoonResponse>> cached =
                campaignTargetLotCacheRepository.find(today, version, categoryId, pageToken, effectivePageSize);
        if (cached.isPresent()) {
            return cached.get();
        }

        CursorPageResponse<ExpiringSoonResponse> response =
                loadFromDatabase(today, categoryId, pageToken, effectivePageSize);
        campaignTargetLotCacheRepository.put(today, version, categoryId, pageToken, effectivePageSize, response);
        return response;
    }

    private CursorPageResponse<ExpiringSoonResponse> loadFromDatabase(
            LocalDate today, Long categoryId, String pageToken, int effectivePageSize) {

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

        Map<Long, ProductOptionInfo> infoByStockLotId = campaignTargetLotProductInfoService.findByStockLotId(batch);

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

}
