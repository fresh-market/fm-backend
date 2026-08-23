package com.freshmarket.stock.domain.service;

import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.domain.ExpiringSoonJudge;
import com.freshmarket.stock.domain.dto.ExpiringSoonResponse;
import com.freshmarket.stock.domain.dto.StockLotView;
import com.freshmarket.stock.domain.repository.StockLotQueryRepository;
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
 * stock(L1) 은 product(L0) 의 엔티티/Q클래스를 직접 참조할 수 없다
 * (ArchitectureTest: "slices should not depend on each other"). product 정보는
 * 전부 ProductApi 를 거쳐서만 가져온다. QueryDSL 조회는 StockLotQueryRepository 로
 * 분리해, ProductQueryRepository 와 같은 패턴으로 서비스 단위 테스트에서 mock 가능하게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpiringSoonService {

    private static final int DEFAULT_WITHIN_DAYS = 3;

    private final StockLotQueryRepository stockLotQueryRepository;
    private final ProductApi productApi;

    public List<ExpiringSoonResponse> getExpiringSoonProducts(int withinDays, Long categoryId) {
        int effectiveWithinDays = withinDays > 0 ? withinDays : DEFAULT_WITHIN_DAYS;
        LocalDate judgmentStart = LocalDate.now().plusDays(effectiveWithinDays);

        List<StockLotView> lots = stockLotQueryRepository.findAvailableLots();
        if (lots.isEmpty()) {
            return List.of();
        }

        List<Long> productOptionIds = lots.stream()
                .map(StockLotView::productOptionId)
                .distinct()
                .toList();
        List<ProductOptionInfo> infos = productApi.findOptionInfos(productOptionIds);
        Map<Long, ProductOptionInfo> infoByOptionId = infos.stream()
                .collect(Collectors.toMap(ProductOptionInfo::productOptionId, Function.identity()));

        return lots.stream()
                .filter(lot -> infoByOptionId.containsKey(lot.productOptionId()))
                .map(lot -> Map.entry(lot, infoByOptionId.get(lot.productOptionId())))
                .filter(e -> e.getValue().purchasable())
                .filter(e -> categoryId == null || categoryId.equals(e.getValue().categoryId()))
                .filter(e -> ExpiringSoonJudge.isExpiringSoon(
                        e.getKey(), e.getValue().saleAvailableDaysFromExpiry(), judgmentStart))
                .map(e -> ExpiringSoonResponse.from(e.getValue()))
                .distinct()
                .toList();
    }
}
