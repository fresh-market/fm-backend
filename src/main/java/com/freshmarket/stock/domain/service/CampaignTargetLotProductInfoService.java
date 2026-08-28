package com.freshmarket.stock.domain.service;

import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.domain.entity.CampaignTargetLot;
import com.freshmarket.stock.domain.entity.StockLot;
import com.freshmarket.stock.domain.repository.StockLotRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 캠페인 대상 로트에 상품 정보를 붙인다.
 *
 * 관리자용(AdminCampaignTargetLotService)과 회원용(ExpiringSoonService)이 같은 확정본을
 * 읽으면서 이 조합 과정도 똑같이 필요해, 양쪽에 복사돼 있던 것을 여기로 모았다 (MNT-3-01).
 * 노출 범위는 각자의 응답 DTO 가 정하므로 이 클래스는 조회와 매핑까지만 맡는다.
 *
 * ArchitectureTest 의 layeredArchitecture 는 같은 층 안의 호출을 허용하므로
 * 서비스가 이 서비스를 주입받아 쓰는 것이 계층 규칙에 어긋나지 않는다
 * (AdminLotService, MemberLoginService 등 기존 선례와 같다).
 *
 * 트랜잭션을 걸지 않는다 — 안에서 ProductApi 외부 호출을 하므로, 감싸면 그 호출이
 * 읽기 트랜잭션 안에 들어간다 (DI-4-02). 호출하는 두 서비스도 같은 이유로 안 건다.
 */
@Service
@RequiredArgsConstructor
public class CampaignTargetLotProductInfoService {

    private final StockLotRepository stockLotRepository;
    private final ProductApi productApi;

    /*
     * 대상 로트를 stockLotId → 상품 정보로 매핑한다.
     *
     * CampaignTargetLot 은 stockLotId 만 갖고 있어, StockLot 을 한 번 더 조회해
     * productOptionId 를 얻고 그것으로 ProductApi 를 부른다. 두 단계 모두 IN 조회
     * 한 번씩이라 대상 수와 무관하게 쿼리는 두 번이다.
     *
     * 상품 정보를 찾지 못한 로트는 결과에서 빠진다 — 호출부가 그 로트를 목록에서 제외한다.
     */
    public Map<Long, ProductOptionInfo> findByStockLotId(List<CampaignTargetLot> targetLots) {
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
