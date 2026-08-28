package com.freshmarket.stock.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.domain.dto.ExpiringSoonResponse;
import com.freshmarket.stock.domain.entity.CampaignTargetLot;
import com.freshmarket.stock.domain.repository.CampaignTargetLotCacheRepository;
import com.freshmarket.stock.domain.repository.CampaignTargetLotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * ExpiringSoonService 가 확정된 캠페인 대상(campaign_target_lot)을 읽어 상품 정보를 붙이고
 * 커서 페이지네이션을 만드는 흐름을 검증한다. 임박 판정 자체는 여기서 하지 않는다 —
 * CampaignTargetLotBatch 가 이미 확정해 둔 것을 읽기만 하기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class ExpiringSoonServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    @Mock
    private CampaignTargetLotRepository campaignTargetLotRepository;

    @Mock
    private CampaignTargetLotProductInfoService campaignTargetLotProductInfoService;

    @Mock
    private CampaignTargetLotCacheRepository campaignTargetLotCacheRepository;

    private ExpiringSoonService expiringSoonService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        expiringSoonService = new ExpiringSoonService(campaignTargetLotRepository,
                campaignTargetLotProductInfoService, campaignTargetLotCacheRepository, clock);
    }

    // 캐시가 비어 있는 기본 상황. 캐시 적중을 보는 테스트만 이 스텁을 덮어쓴다
    private void 캐시_미스() {
        when(campaignTargetLotCacheRepository.find(any(), any(), any(), anyInt()))
                .thenReturn(Optional.empty());
    }

    private CampaignTargetLot targetLot(long stockLotId, int targetRank) {
        return CampaignTargetLot.register(TODAY, stockLotId, new BigDecimal("0.0500"), 40, targetRank);
    }

    private ProductOptionInfo info(long productOptionId, long categoryId, boolean purchasable) {
        return new ProductOptionInfo(
                12L, categoryId, productOptionId, "감귤", "1kg", 12900, purchasable, 10);
    }

    @Test
    void 오늘_확정된_대상이_없으면_빈_목록을_준다() {
        캐시_미스();
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), any())).thenReturn(List.of());

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(null, null, 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextPageToken()).isNull();
    }

    @Test
    void 대상_로트의_상품_정보를_붙여_돌려준다() {
        캐시_미스();
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), any())).thenReturn(List.of(targetLot(100L, 1)));
        when(campaignTargetLotProductInfoService.findByStockLotId(any()))
                .thenReturn(Map.of(100L, info(31L, 4L, true)));

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(null, null, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).productName()).isEqualTo("감귤");
        assertThat(result.items().get(0).productOptionId()).isEqualTo(31L);
        assertThat(result.nextPageToken()).isNull();
    }

    @Test
    void 구매할_수_없는_상품은_빠진다() {
        캐시_미스();
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), any())).thenReturn(List.of(targetLot(100L, 1)));
        when(campaignTargetLotProductInfoService.findByStockLotId(any()))
                .thenReturn(Map.of(100L, info(31L, 4L, false)));

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(null, null, 20);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void 상품_정보를_찾지_못한_로트는_빠진다() {
        캐시_미스();
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), any())).thenReturn(List.of(targetLot(100L, 1)));
        when(campaignTargetLotProductInfoService.findByStockLotId(any())).thenReturn(Map.of());

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(null, null, 20);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void 카테고리로_거를_수_있다() {
        캐시_미스();
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), any())).thenReturn(List.of(targetLot(100L, 1)));
        when(campaignTargetLotProductInfoService.findByStockLotId(any()))
                .thenReturn(Map.of(100L, info(31L, 4L, true)));

        CursorPageResponse<ExpiringSoonResponse> matched =
                expiringSoonService.getExpiringSoonProducts(4L, null, 20);

        assertThat(matched.items()).hasSize(1);
    }

    @Test
    void 다른_카테고리를_주면_빈_목록을_준다() {
        캐시_미스();
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), any())).thenReturn(List.of(targetLot(100L, 1)));
        when(campaignTargetLotProductInfoService.findByStockLotId(any()))
                .thenReturn(Map.of(100L, info(31L, 4L, true)));

        CursorPageResponse<ExpiringSoonResponse> unmatched =
                expiringSoonService.getExpiringSoonProducts(999L, null, 20);

        assertThat(unmatched.items()).isEmpty();
    }

    @Test
    void 결과가_pageSize보다_많으면_다음_페이지_토큰을_준다() {
        // given — pageSize 1, 여유분(FETCH_MULTIPLIER=2)까지 채우고도 남아 다음 페이지가 있다
        캐시_미스();
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), any()))
                .thenReturn(List.of(targetLot(100L, 1), targetLot(200L, 2), targetLot(300L, 3)));
        when(campaignTargetLotProductInfoService.findByStockLotId(any()))
                .thenReturn(Map.of(100L, info(31L, 4L, true), 200L, info(32L, 4L, true)));

        CursorPageResponse<ExpiringSoonResponse> firstPage =
                expiringSoonService.getExpiringSoonProducts(null, null, 1);

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextPageToken()).isNotNull();
    }

    @Test
    void 캐시가_있으면_DB를_보지_않는다() {
        CursorPageResponse<ExpiringSoonResponse> cached = CursorPageResponse.of(
                List.of(new ExpiringSoonResponse(12L, "감귤", 31L, "1kg", 12900)), null);
        when(campaignTargetLotCacheRepository.find(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(cached));

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(null, null, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).productName()).isEqualTo("감귤");
        verifyNoInteractions(campaignTargetLotRepository, campaignTargetLotProductInfoService);
    }

    @Test
    void 캐시를_못_읽어도_DB로_내려가_응답한다() {
        // given — 캐시가 비어 있는 것과 Redis 장애는 호출부에 같은 의미다(둘 다 Optional.empty)
        캐시_미스();
        when(campaignTargetLotRepository.findByTargetDateAndTargetRankGreaterThanOrderByTargetRankAsc(
                eq(TODAY), eq(0), any())).thenReturn(List.of(targetLot(100L, 1)));
        when(campaignTargetLotProductInfoService.findByStockLotId(any()))
                .thenReturn(Map.of(100L, info(31L, 4L, true)));

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(null, null, 20);

        assertThat(result.items()).hasSize(1);
    }
}
