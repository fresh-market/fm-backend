package com.freshmarket.stock.domain.service;

import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.domain.dto.ExpiringSoonResponse;
import com.freshmarket.stock.domain.dto.StockLotView;
import com.freshmarket.stock.domain.repository.StockLotQueryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ExpiringSoonService 가 조회 결과를 조합하고 커서 페이지네이션을 만드는 흐름을 검증한다
@ExtendWith(MockitoExtension.class)
class ExpiringSoonServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    @Mock
    private StockLotQueryRepository stockLotQueryRepository;

    @Mock
    private ProductApi productApi;

    private ExpiringSoonService expiringSoonService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        expiringSoonService = new ExpiringSoonService(stockLotQueryRepository, productApi, clock);
    }

    @Test
    void 로트_조회_결과가_없으면_빈_목록을_준다() {
        // given — pageSize 20 이면 여유분(FETCH_MULTIPLIER=2) 반영해 40건 조회한다
        when(stockLotQueryRepository.findAvailableLots(isNull(), eq(40))).thenReturn(List.of());

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(3, null, null, 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextPageToken()).isNull();
    }

    @Test
    void 임박한_로트가_있으면_상품_정보와_함께_돌려준다() {
        StockLotView lot = new StockLotView(31L, TODAY.plusDays(13));
        ProductOptionInfo info = new ProductOptionInfo(
                12L, 4L, 31L, "감귤", "1kg", 12900, true, 10);

        when(stockLotQueryRepository.findAvailableLots(isNull(), eq(40))).thenReturn(List.of(lot));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of(info));

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(3, null, null, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).productName()).isEqualTo("감귤");
    }

    @Test
    void 필터링_후에도_pageSize를_넘으면_다음_페이지_토큰을_준다() {
        // given — pageSize 1, fetchSize 2. 여유분으로 2건 다 필터링을 통과하는 상황
        StockLotView lot1 = new StockLotView(31L, TODAY.plusDays(13));
        StockLotView lot2 = new StockLotView(32L, TODAY.plusDays(13));
        ProductOptionInfo info1 = new ProductOptionInfo(
                12L, 4L, 31L, "감귤", "1kg", 12900, true, 10);
        ProductOptionInfo info2 = new ProductOptionInfo(
                13L, 4L, 32L, "복숭아", "1kg", 15000, true, 10);

        when(stockLotQueryRepository.findAvailableLots(isNull(), eq(2)))
                .thenReturn(List.of(lot1, lot2));
        when(productApi.findOptionInfos(List.of(31L, 32L))).thenReturn(List.of(info1, info2));

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(3, null, null, 1);

        // then — 필터링 후 2건 남았는데 pageSize=1 이라, 1건만 응답하고 다음 토큰을 준다
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).productName()).isEqualTo("감귤");
        assertThat(result.nextPageToken()).isNotNull();
        PageCursor decoded = PageTokens.decode(result.nextPageToken());
        assertThat(decoded.id()).isEqualTo(31L);
    }

    @Test
    void 여유분_중_일부만_필터링을_통과해도_있는_만큼_준다() {
        // given — pageSize 1, fetchSize 2 로 2건 가져왔지만 1건만 purchasable 이다.
        // 필터링 후 1건뿐이라 pageSize 를 못 넘어서더 이상 페이지가 없는 것처럼 보인다
        StockLotView lot1 = new StockLotView(31L, TODAY.plusDays(13));
        StockLotView lot2 = new StockLotView(32L, TODAY.plusDays(13));
        ProductOptionInfo info1 = new ProductOptionInfo(
                12L, 4L, 31L, "감귤", "1kg", 12900, true, 10);
        ProductOptionInfo info2 = new ProductOptionInfo(
                13L, 4L, 32L, "복숭아", "1kg", 15000, false, 10);   // purchasable = false

        when(stockLotQueryRepository.findAvailableLots(isNull(), eq(2)))
                .thenReturn(List.of(lot1, lot2));
        when(productApi.findOptionInfos(List.of(31L, 32L))).thenReturn(List.of(info1, info2));

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(3, null, null, 1);

        // then — 여유분(lot2)이 걸러져 필터링 결과가 1건뿐이라, pageSize(1)를 안 넘는다
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void 상품_정보를_못_찾은_로트는_결과에서_제외된다() {
        StockLotView lot = new StockLotView(31L, TODAY.plusDays(13));

        when(stockLotQueryRepository.findAvailableLots(isNull(), eq(40))).thenReturn(List.of(lot));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of());

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(3, null, null, 20);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void categoryId가_다르면_결과에서_제외된다() {
        StockLotView lot = new StockLotView(31L, TODAY.plusDays(13));
        ProductOptionInfo info = new ProductOptionInfo(
                12L, 4L, 31L, "감귤", "1kg", 12900, true, 10);

        when(stockLotQueryRepository.findAvailableLots(isNull(), eq(40))).thenReturn(List.of(lot));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of(info));

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(3, 999L, null, 20);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void 판매중단된_옵션은_결과에서_제외된다() {
        StockLotView lot = new StockLotView(31L, TODAY.plusDays(13));
        ProductOptionInfo info = new ProductOptionInfo(
                12L, 4L, 31L, "감귤", "1kg", 12900, false, 10);

        when(stockLotQueryRepository.findAvailableLots(isNull(), eq(40))).thenReturn(List.of(lot));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of(info));

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(3, null, null, 20);

        assertThat(result.items()).isEmpty();
    }
}