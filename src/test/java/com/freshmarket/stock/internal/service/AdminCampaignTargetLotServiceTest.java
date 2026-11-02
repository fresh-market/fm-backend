package com.freshmarket.stock.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.freshmarket.common.response.PageCursor;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.internal.dto.AdminCampaignTargetLotListResponse;
import com.freshmarket.stock.internal.batch.CampaignTargetLotRebuildService;
import com.freshmarket.stock.internal.dto.AdminCampaignTargetLotRebuildResponse;
import com.freshmarket.stock.internal.entity.CampaignTargetLot;
import com.freshmarket.stock.internal.repository.CampaignTargetLotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AdminCampaignTargetLotServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 25);

    @Mock
    private CampaignTargetLotRepository campaignTargetLotRepository;

    @Mock
    private CampaignTargetLotProductInfoService campaignTargetLotProductInfoService;

    @Mock
    private CampaignTargetLotRebuildService campaignTargetLotRebuildService;

    private AdminCampaignTargetLotService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        service = new AdminCampaignTargetLotService(
                campaignTargetLotRepository, campaignTargetLotProductInfoService,
                campaignTargetLotRebuildService, clock);
    }

    private CampaignTargetLot targetLot(long stockLotId, int targetRank) {
        return CampaignTargetLot.register(TODAY, stockLotId, new BigDecimal("0.0500"), 40, targetRank);
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
        when(campaignTargetLotProductInfoService.findByStockLotId(any()))
                .thenReturn(Map.of(100L, info(31L)));

        AdminCampaignTargetLotListResponse result = service.find(null, 20);

        assertThat(result.targets()).hasSize(1);
        assertThat(result.targets().get(0).productName()).isEqualTo("감귤");
        assertThat(result.targets().get(0).stockLotId()).isEqualTo(100L);
        assertThat(result.targets().get(0).targetRank()).isEqualTo(1);
        assertThat(result.nextPageToken()).isNull();
    }

    @Test
    void 상품_정보를_찾지_못한_로트는_목록에서_빠진다() {
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), any())).thenReturn(List.of(targetLot(100L, 1)));
        when(campaignTargetLotProductInfoService.findByStockLotId(any())).thenReturn(Map.of());

        AdminCampaignTargetLotListResponse result = service.find(null, 20);

        assertThat(result.targets()).isEmpty();
    }

    @Test
    void 결과가_pageSize보다_많으면_다음_페이지_토큰을_준다() {
        // given — pageSize 1 인데 2건이 온다(리포지토리가 pageSize + 1 건을 준다)
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), any()))
                .thenReturn(List.of(targetLot(100L, 1), targetLot(200L, 2)));
        when(campaignTargetLotProductInfoService.findByStockLotId(any()))
                .thenReturn(Map.of(100L, info(31L)));

        AdminCampaignTargetLotListResponse result = service.find(null, 1);

        assertThat(result.targets()).hasSize(1);
        assertThat(result.nextPageToken()).isNotNull();
    }

    @Test
    void 커서를_주면_그_순위_다음부터_조회한다() {
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(3), any())).thenReturn(List.of(targetLot(400L, 4)));
        when(campaignTargetLotProductInfoService.findByStockLotId(any()))
                .thenReturn(Map.of(400L, info(34L)));

        AdminCampaignTargetLotListResponse result = service.find(new PageCursor(3L, null), 20);

        assertThat(result.targets()).hasSize(1);
        assertThat(result.targets().get(0).targetRank()).isEqualTo(4);
    }

    @Test
    void 페이지_크기_상한을_서버가_강제한다() {
        // given — 클라이언트가 9999 를 넘겨도 서버가 100 으로 자른다 (FUN-3-04, SEC-3-03)
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), eq(PageRequest.of(0, 101)))).thenReturn(List.of());

        AdminCampaignTargetLotListResponse result = service.find(null, 9999);

        assertThat(result.targets()).isEmpty();
    }

    /*
     * 재실행은 확정 로직을 그대로 부르고 결과 건수만 돌려준다.
     * 자정 배치가 실패했을 때 다음 자정까지 기다리지 않고 복구하는 경로다.
     */
    @Test
    void 재실행하면_확정_건수를_돌려준다() {
        when(campaignTargetLotRebuildService.rebuild()).thenReturn(12);

        AdminCampaignTargetLotRebuildResponse response = service.rebuildToday();

        assertThat(response.targetDate()).isEqualTo(TODAY);
        assertThat(response.confirmedCount()).isEqualTo(12);
    }

    // 후보가 없는 날은 0 이 정상이다. 실패와 구분되어야 한다
    @Test
    void 확정된_대상이_없으면_0을_돌려준다() {
        when(campaignTargetLotRebuildService.rebuild()).thenReturn(0);

        assertThat(service.rebuildToday().confirmedCount()).isZero();
    }
}
