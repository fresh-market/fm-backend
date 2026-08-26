package com.freshmarket.stock.domain.service;

import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.domain.dto.AdminCampaignTargetLotListResponse;
import com.freshmarket.stock.domain.dto.AdminCampaignTargetLotResponse;
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
import org.springframework.stereotype.Service;

/*
 * 관리자 화면에서 오늘의 캠페인 대상 로트를 조회한다.
 *
 * 계산은 하지 않는다 — CampaignTargetLotBatch 가 자정에 이미 확정해 둔 값을
 * 그대로 읽기만 한다. "동일 기준일로 재조회 시 항상 동일 결과 반환" 요구사항이
 * 여기가 아니라 배치의 확정 시점에 이미 보장돼 있기 때문이다.
 *
 * 클래스에 @Transactional 을 걸지 않는다 — findToday() 중간에 productApi 호출이
 * 끼어 있어, 감싸면 그 호출이 읽기 트랜잭션 안에 들어간다(DI-4-02, domain-package-
 * boundary-guideline.md 7장과 같은 문제). 여기서 쓰는 조회 메서드들은 전부 Spring
 * Data JPA 리포지토리가 기본으로 제공하는 것이라, 감싸지 않아도 각자 자기 트랜잭션
 * 안에서 실행된다 — 이 서비스가 DB 에 쓰기를 전혀 하지 않으므로 그걸로 충분하다.
 */
@Service
public class AdminCampaignTargetLotService {

    private final CampaignTargetLotRepository campaignTargetLotRepository;
    private final StockLotRepository stockLotRepository;
    private final ProductApi productApi;
    private final Clock clock;

    public AdminCampaignTargetLotService(CampaignTargetLotRepository campaignTargetLotRepository,
            StockLotRepository stockLotRepository, ProductApi productApi, Clock clock) {
        this.campaignTargetLotRepository = campaignTargetLotRepository;
        this.stockLotRepository = stockLotRepository;
        this.productApi = productApi;
        this.clock = clock;
    }

    /*
     * CampaignTargetLot 은 stockLotId 만 갖고 있어, 상품/옵션 정보를 보여주려면
     * StockLot 을 한 번 더 조회해 productOptionId 를 얻고, 그걸로 ProductApi 를 불러야 한다.
     * 두 단계 조회가 되지만 각 단계가 IN 조회 한 번씩이라 대상 수와 무관하게 쿼리는 세 번이다.
     * 다만 대상이 하위 10% 전체라 건수 상한이 없어, 후보가 크게 늘면 ProductApi 로 넘기는
     * 옵션 ID 목록도 함께 커진다. 페이지네이션이 필요해지는 지점이 여기다.
     */
    public AdminCampaignTargetLotListResponse findToday() {
        LocalDate today = LocalDate.now(clock);
        List<CampaignTargetLot> targetLots =
                campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(today);

        if (targetLots.isEmpty()) {
            return new AdminCampaignTargetLotListResponse(today, List.of());
        }

        List<Long> stockLotIds = targetLots.stream().map(CampaignTargetLot::getStockLotId).toList();
        Map<Long, StockLot> stockLotById = stockLotRepository.findAllById(stockLotIds).stream()
                .collect(Collectors.toMap(StockLot::getId, Function.identity()));

        List<Long> productOptionIds = stockLotById.values().stream()
                .map(StockLot::getProductOptionId)
                .toList();
        Map<Long, ProductOptionInfo> infoByOptionId = productApi.findOptionInfos(productOptionIds).stream()
                .collect(Collectors.toMap(ProductOptionInfo::productOptionId, Function.identity()));

        List<AdminCampaignTargetLotResponse> targets = targetLots.stream()
                .map(lot -> {
                    StockLot stockLot = stockLotById.get(lot.getStockLotId());
                    ProductOptionInfo info = infoByOptionId.get(stockLot.getProductOptionId());
                    return AdminCampaignTargetLotResponse.of(lot, info);
                })
                .toList();

        return new AdminCampaignTargetLotListResponse(today, targets);
    }
}
