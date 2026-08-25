package com.freshmarket.stock.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.domain.dto.CampaignTargetLotListResponse;
import com.freshmarket.stock.domain.entity.CampaignTargetLot;
import com.freshmarket.stock.domain.entity.StockLot;
import com.freshmarket.stock.domain.repository.CampaignTargetLotRepository;
import com.freshmarket.stock.domain.repository.StockLotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminCampaignTargetLotServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 25);

    @Mock
    private CampaignTargetLotRepository campaignTargetLotRepository;

    @Mock
    private StockLotRepository stockLotRepository;

    @Mock
    private ProductApi productApi;

    private AdminCampaignTargetLotService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        service = new AdminCampaignTargetLotService(
                campaignTargetLotRepository, stockLotRepository, productApi, clock);
    }

    @Test
    void 오늘_대상이_없으면_빈_목록을_준다() {
        when(campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(TODAY))
                .thenReturn(List.of());

        CampaignTargetLotListResponse result = service.findToday();

        assertThat(result.targetDate()).isEqualTo(TODAY);
        assertThat(result.targets()).isEmpty();
    }

    @Test
    void 대상_로트의_상품_정보를_조합해서_돌려준다() {
        // given
        CampaignTargetLot targetLot = CampaignTargetLot.register(
                TODAY, 100L, new BigDecimal("0.0500"), 40, 1);
        ReflectionTestUtils.setField(targetLot, "id", 1L);

        StockLot stockLot = StockLot.register("req-1", 31L, TODAY.minusDays(5), TODAY.plusDays(3), 50);
        ReflectionTestUtils.setField(stockLot, "id", 100L);

        ProductOptionInfo info = new ProductOptionInfo(
                12L, 4L, 31L, "감귤", "1kg", 12900, true, 3);

        when(campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(TODAY))
                .thenReturn(List.of(targetLot));
        when(stockLotRepository.findAllById(List.of(100L))).thenReturn(List.of(stockLot));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of(info));

        // when
        CampaignTargetLotListResponse result = service.findToday();

        // then
        assertThat(result.targets()).hasSize(1);
        assertThat(result.targets().get(0).productName()).isEqualTo("감귤");
        assertThat(result.targets().get(0).stockLotId()).isEqualTo(100L);
        assertThat(result.targets().get(0).targetRank()).isEqualTo(1);
    }
}
