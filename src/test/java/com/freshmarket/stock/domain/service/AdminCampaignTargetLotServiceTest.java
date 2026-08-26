package com.freshmarket.stock.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.freshmarket.common.response.PageCursor;
import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.domain.dto.AdminCampaignTargetLotListResponse;
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

    private CampaignTargetLot targetLot(long stockLotId, int targetRank) {
        return CampaignTargetLot.register(TODAY, stockLotId, new BigDecimal("0.0500"), 40, targetRank);
    }

    private StockLot stockLot(long id, long productOptionId) {
        StockLot lot = StockLot.register(
                "req-" + id, productOptionId, TODAY.minusDays(5), TODAY.plusDays(12), 50);
        ReflectionTestUtils.setField(lot, "id", id);
        return lot;
    }

    private ProductOptionInfo info(long productOptionId) {
        return new ProductOptionInfo(12L, 4L, productOptionId, "감귤", "1kg", 12900, true, 10);
    }

    @Test
    void 오늘_대상이_없으면_빈_목록을_준다() {
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), any())).thenReturn(List.of());

        AdminCampaignTargetLotListResponse result = service.find(null, 20);

        assertThat(result.targetDate()).isEqualTo(TODAY);
        assertThat(result.targets()).isEmpty();
        assertThat(result.nextPageToken()).isNull();
    }

    @Test
    void 대상_로트의_상품_정보를_조합해서_돌려준다() {
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), any())).thenReturn(List.of(targetLot(100L, 1)));
        when(stockLotRepository.findAllById(List.of(100L))).thenReturn(List.of(stockLot(100L, 31L)));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of(info(31L)));

        AdminCampaignTargetLotListResponse result = service.find(null, 20);

        assertThat(result.targets()).hasSize(1);
        assertThat(result.targets().get(0).productName()).isEqualTo("감귤");
        assertThat(result.targets().get(0).stockLotId()).isEqualTo(100L);
        assertThat(result.targets().get(0).targetRank()).isEqualTo(1);
        assertThat(result.nextPageToken()).isNull();
    }

    @Test
    void 결과가_pageSize보다_많으면_다음_페이지_토큰을_준다() {
        // given — pageSize 1 인데 2건이 온다(리포지토리가 pageSize + 1 건을 준다)
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), any()))
                .thenReturn(List.of(targetLot(100L, 1), targetLot(200L, 2)));
        when(stockLotRepository.findAllById(List.of(100L))).thenReturn(List.of(stockLot(100L, 31L)));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of(info(31L)));

        AdminCampaignTargetLotListResponse result = service.find(null, 1);

        assertThat(result.targets()).hasSize(1);
        assertThat(result.nextPageToken()).isNotNull();
    }

    @Test
    void 커서를_주면_그_순위_다음부터_조회한다() {
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(3), any())).thenReturn(List.of(targetLot(400L, 4)));
        when(stockLotRepository.findAllById(List.of(400L))).thenReturn(List.of(stockLot(400L, 34L)));
        when(productApi.findOptionInfos(List.of(34L))).thenReturn(List.of(info(34L)));

        AdminCampaignTargetLotListResponse result = service.find(new PageCursor(3L, null), 20);

        assertThat(result.targets()).hasSize(1);
        assertThat(result.targets().get(0).targetRank()).isEqualTo(4);
    }

    @Test
    void 페이지_크기_상한을_서버가_강제한다() {
        // given — 클라이언트가 9999 를 넘겨도 서버가 100 으로 자른다 (FUN-3-04, SEC-3-03)
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), eq(org.springframework.data.domain.PageRequest.of(0, 101))))
                .thenReturn(List.of());

        AdminCampaignTargetLotListResponse result = service.find(null, 9999);

        assertThat(result.targets()).isEmpty();
    }
}
