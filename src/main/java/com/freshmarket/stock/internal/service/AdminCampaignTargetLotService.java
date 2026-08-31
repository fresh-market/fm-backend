package com.freshmarket.stock.internal.service;

import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.internal.dto.AdminCampaignTargetLotListResponse;
import com.freshmarket.stock.internal.dto.AdminCampaignTargetLotResponse;
import com.freshmarket.stock.internal.entity.CampaignTargetLot;
import com.freshmarket.stock.internal.repository.CampaignTargetLotRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/*
 * 관리자 화면에서 오늘의 캠페인 대상 로트를 조회한다.
 *
 * 계산은 하지 않는다 — CampaignTargetLotBatch 가 자정에 이미 확정해 둔 값을
 * 그대로 읽기만 한다. "동일 기준일로 재조회 시 항상 동일 결과 반환" 요구사항이
 * 여기가 아니라 배치의 확정 시점에 이미 보장돼 있기 때문이다.
 *
 * 대상 건수에 상한이 없어(소진율 하위 10% 전체) 커서 페이지네이션을 둔다
 * (API-3-04, API-5-01, FUN-3-03). target_rank 는 기준일 안에서 1부터 순차라
 * 커서 축으로 그대로 쓴다. 페이지 크기 상한은 서버가 강제한다 (FUN-3-04, SEC-3-03).
 *
 * 클래스에 @Transactional 을 걸지 않는다 — find() 중간에 productApi 호출이
 * 끼어 있어, 감싸면 그 호출이 읽기 트랜잭션 안에 들어간다(DI-4-02, domain-package-
 * boundary-guideline.md 7장과 같은 문제). 여기서 쓰는 조회 메서드들은 전부 Spring
 * Data JPA 리포지토리가 기본으로 제공하는 것이라, 감싸지 않아도 각자 자기 트랜잭션
 * 안에서 실행된다 — 이 서비스가 DB 에 쓰기를 전혀 하지 않으므로 그걸로 충분하다.
 */
@Service
public class AdminCampaignTargetLotService {

    // AdminLotService 와 같은 값·같은 근거
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final CampaignTargetLotRepository campaignTargetLotRepository;
    private final CampaignTargetLotProductInfoService campaignTargetLotProductInfoService;
    private final Clock clock;

    public AdminCampaignTargetLotService(CampaignTargetLotRepository campaignTargetLotRepository,
            CampaignTargetLotProductInfoService campaignTargetLotProductInfoService, Clock clock) {
        this.campaignTargetLotRepository = campaignTargetLotRepository;
        this.campaignTargetLotProductInfoService = campaignTargetLotProductInfoService;
        this.clock = clock;
    }

    /*
     * 오늘 확정된 대상을 순위대로 한 페이지 조회한다.
     * 리포지토리가 pageSize + 1건을 주므로 초과분을 잘라내고 다음 페이지 여부를 판단한다.
     *
     * CampaignTargetLot 은 stockLotId 만 갖고 있어, 상품/옵션 정보를 보여주려면
     * StockLot 을 한 번 더 조회해 productOptionId 를 얻고, 그걸로 ProductApi 를 불러야 한다.
     * 두 단계 모두 IN 조회 한 번씩이라 페이지 크기와 무관하게 쿼리는 세 번이다.
     */
    public AdminCampaignTargetLotListResponse find(PageCursor cursor, int pageSize) {
        LocalDate today = LocalDate.now(clock);
        int effectivePageSize = resolvePageSize(pageSize);
        int afterRank = cursor != null ? cursor.id().intValue() : 0;

        List<CampaignTargetLot> found = campaignTargetLotRepository
                .findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                        today, afterRank, PageRequest.of(0, effectivePageSize + 1));

        boolean hasNext = found.size() > effectivePageSize;
        List<CampaignTargetLot> page = hasNext ? found.subList(0, effectivePageSize) : found;

        if (page.isEmpty()) {
            return new AdminCampaignTargetLotListResponse(today, List.of(), null);
        }

        Map<Long, ProductOptionInfo> infoByStockLotId = campaignTargetLotProductInfoService.findByStockLotId(page);

        List<AdminCampaignTargetLotResponse> targets = page.stream()
                .filter(lot -> infoByStockLotId.containsKey(lot.getStockLotId()))
                .map(lot -> AdminCampaignTargetLotResponse.of(lot, infoByStockLotId.get(lot.getStockLotId())))
                .toList();

        return new AdminCampaignTargetLotListResponse(today, targets, nextTokenOf(page, hasNext));
    }

    private static int resolvePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    // 다음 페이지 토큰. 마지막 행의 순위를 커서로 쓴다(정렬이 targetRank asc 고정이라 축이 하나다)
    private static String nextTokenOf(List<CampaignTargetLot> page, boolean hasNext) {
        if (!hasNext || page.isEmpty()) {
            return null;
        }
        return PageTokens.encode(new PageCursor((long) page.get(page.size() - 1).getTargetRank(), null));
    }

}
