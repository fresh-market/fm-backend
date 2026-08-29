package com.freshmarket.stock.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.domain.entity.CampaignTargetLot;
import com.freshmarket.stock.domain.entity.StockLot;
import com.freshmarket.stock.domain.repository.StockLotRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// 대상 로트 → 상품 정보 매핑을 검증한다. 관리자용/회원용 조회가 공유하는 협력자다 (MNT-3-01)
@ExtendWith(MockitoExtension.class)
class CampaignTargetLotProductInfoServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

    @Mock
    private StockLotRepository stockLotRepository;

    @Mock
    private ProductApi productApi;

    @InjectMocks
    private CampaignTargetLotProductInfoService service;

    private CampaignTargetLot targetLot(long stockLotId) {
        return CampaignTargetLot.register(TODAY, stockLotId, new BigDecimal("0.0500"), 40, 1);
    }

    // id 는 엔티티가 채우는 값이라 테스트에서 직접 넣는다
    private StockLot stockLot(long id, long productOptionId) {
        StockLot lot = StockLot.register(
                "req-" + id, productOptionId, TODAY.minusDays(2), TODAY.plusDays(12), 100);
        ReflectionTestUtils.setField(lot, "id", id);
        return lot;
    }

    private ProductOptionInfo info(long productOptionId) {
        return new ProductOptionInfo(12L, 4L, productOptionId, "감귤", "1kg", 12900, true, 10);
    }

    @Test
    void 로트_ID로_상품_정보를_찾아_붙인다() {
        when(stockLotRepository.findAllById(List.of(100L))).thenReturn(List.of(stockLot(100L, 31L)));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of(info(31L)));

        Map<Long, ProductOptionInfo> result = service.findByStockLotId(List.of(targetLot(100L)));

        assertThat(result).containsOnlyKeys(100L);
        assertThat(result.get(100L).productOptionId()).isEqualTo(31L);
    }

    @Test
    void 같은_옵션의_로트가_여러_개면_옵션_조회는_한_번만_한다() {
        // given — 로트 둘이 같은 옵션(31)을 가리킨다. ProductApi 에는 중복 없이 넘어가야 한다
        when(stockLotRepository.findAllById(List.of(100L, 200L)))
                .thenReturn(List.of(stockLot(100L, 31L), stockLot(200L, 31L)));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of(info(31L)));

        Map<Long, ProductOptionInfo> result =
                service.findByStockLotId(List.of(targetLot(100L), targetLot(200L)));

        assertThat(result).containsOnlyKeys(100L, 200L);
    }

    @Test
    void 상품_정보가_없는_로트는_결과에서_빠진다() {
        when(stockLotRepository.findAllById(List.of(100L))).thenReturn(List.of(stockLot(100L, 31L)));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of());

        Map<Long, ProductOptionInfo> result = service.findByStockLotId(List.of(targetLot(100L)));

        assertThat(result).isEmpty();
    }

    @Test
    void 로트가_없으면_빈_결과를_준다() {
        when(stockLotRepository.findAllById(List.of())).thenReturn(List.of());
        when(productApi.findOptionInfos(List.of())).thenReturn(List.of());

        Map<Long, ProductOptionInfo> result = service.findByStockLotId(List.of());

        assertThat(result).isEmpty();
    }
}
