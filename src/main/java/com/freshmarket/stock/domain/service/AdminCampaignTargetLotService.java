package com.freshmarket.stock.domain.service;

import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.domain.dto.CampaignTargetLotListResponse;
import com.freshmarket.stock.domain.dto.CampaignTargetLotResponse;
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
     * 두 단계 조회가 되지만, 대상 로트가 최대 3건뿐이라 성능 부담은 없다.
     */
    public CampaignTargetLotListResponse findToday() {
        LocalDate today = LocalDate.now(clock);
        List<CampaignTargetLot> targetLots =
                campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(today);

        if (targetLots.isEmpty()) {
            return new CampaignTargetLotListResponse(today, List.of());
        }

        List<Long> stockLotIds = targetLots.stream().map(CampaignTargetLot::getStockLotId).toList();
        Map<Long, StockLot> stockLotById = stockLotRepository.findAllById(stockLotIds).stream()
                .collect(Collectors.toMap(StockLot::getId, Function.identity()));

        List<Long> productOptionIds = stockLotById.values().stream()
                .map(StockLot::getProductOptionId)
                .toList();
        Map<Long, ProductOptionInfo> infoByOptionId = productApi.findOptionInfos(productOptionIds).stream()
                .collect(Collectors.toMap(ProductOptionInfo::productOptionId, Function.identity()));

        List<CampaignTargetLotResponse> targets = targetLots.stream()
                .map(lot -> {
                    StockLot stockLot = stockLotById.get(lot.getStockLotId());
                    ProductOptionInfo info = infoByOptionId.get(stockLot.getProductOptionId());
                    return CampaignTargetLotResponse.of(lot, info);
                })
                .toList();

        return new CampaignTargetLotListResponse(today, targets);
    }
}
