package com.freshmarket.stock.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.stock.domain.dto.ExpiringSoonResponse;
import com.freshmarket.stock.domain.dto.StockLotView;
import com.freshmarket.stock.domain.repository.StockLotQueryRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ExpiringSoonService 가 조회 결과를 조합하는 흐름을 검증한다
@ExtendWith(MockitoExtension.class)
class ExpiringSoonServiceTest {

    @Mock
    private StockLotQueryRepository stockLotQueryRepository;

    @Mock
    private ProductApi productApi;

    @InjectMocks
    private ExpiringSoonService expiringSoonService;

    @Test
    void 로트_조회_결과가_없으면_빈_목록을_준다() {
        // given
        when(stockLotQueryRepository.findAvailableLots()).thenReturn(List.of());

        // when
        List<ExpiringSoonResponse> result = expiringSoonService.getExpiringSoonProducts(3, null);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void 임박한_로트가_있으면_상품_정보와_함께_돌려준다() {
        // given
        LocalDate today = LocalDate.now();
        StockLotView lot = new StockLotView(31L, today.plusDays(13));
        ProductOptionInfo info = new ProductOptionInfo(
                12L, 4L, 31L, "감귤", "1kg", 12900, true, 10);

        when(stockLotQueryRepository.findAvailableLots()).thenReturn(List.of(lot));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of(info));

        // when
        List<ExpiringSoonResponse> result = expiringSoonService.getExpiringSoonProducts(3, null);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).productName()).isEqualTo("감귤");
    }

    @Test
    void 상품_정보를_못_찾은_로트는_결과에서_제외된다() {
        // given — productApi 가 빈 목록을 준 경우 (예: 상품 삭제됨)
        LocalDate today = LocalDate.now();
        StockLotView lot = new StockLotView(31L, today.plusDays(13));

        when(stockLotQueryRepository.findAvailableLots()).thenReturn(List.of(lot));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of());

        // when
        List<ExpiringSoonResponse> result = expiringSoonService.getExpiringSoonProducts(3, null);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void categoryId가_다르면_결과에서_제외된다() {
        // given
        LocalDate today = LocalDate.now();
        StockLotView lot = new StockLotView(31L, today.plusDays(13));
        ProductOptionInfo info = new ProductOptionInfo(
                12L, 4L, 31L, "감귤", "1kg", 12900, true, 10);

        when(stockLotQueryRepository.findAvailableLots()).thenReturn(List.of(lot));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of(info));

        // when — 다른 카테고리(999L)로 필터링
        List<ExpiringSoonResponse> result = expiringSoonService.getExpiringSoonProducts(3, 999L);

        // then
        assertThat(result).isEmpty();
    }
    
    @Test
    void 판매중단된_옵션은_결과에서_제외된다() {
        // given
        LocalDate today = LocalDate.now();
        StockLotView lot = new StockLotView(31L, today.plusDays(13));
        ProductOptionInfo info = new ProductOptionInfo(
                12L, 4L, 31L, "감귤", "1kg", 12900, false, 10);   // purchasable = false

        when(stockLotQueryRepository.findAvailableLots()).thenReturn(List.of(lot));
        when(productApi.findOptionInfos(List.of(31L))).thenReturn(List.of(info));

        // when
        List<ExpiringSoonResponse> result = expiringSoonService.getExpiringSoonProducts(3, null);

        // then
        assertThat(result).isEmpty();
    }
}
