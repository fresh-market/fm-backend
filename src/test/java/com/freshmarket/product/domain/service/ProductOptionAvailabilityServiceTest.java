package com.freshmarket.product.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.freshmarket.product.domain.entity.ProductOption;
import com.freshmarket.product.domain.repository.ProductOptionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ProductOptionAvailabilityService의 품절 여부 갱신을 검증한다
@ExtendWith(MockitoExtension.class)
class ProductOptionAvailabilityServiceTest {

    @Mock
    private ProductOptionRepository productOptionRepository;

    @InjectMocks
    private ProductOptionAvailabilityService productOptionAvailabilityService;

    @Test
    void 옵션이_존재하면_품절_여부를_갱신한다() {
        // given
        ProductOption option = ProductOption.register(1L, "1kg", 12900);
        when(productOptionRepository.findById(31L)).thenReturn(Optional.of(option));

        // when
        productOptionAvailabilityService.updateSoldOut(31L, false);

        // then
        assertThat(option.isSoldOut()).isFalse();
    }

    @Test
    void 옵션이_없으면_아무_일도_하지_않는다() {
        // given
        when(productOptionRepository.findById(999L)).thenReturn(Optional.empty());

        // when, then — 예외 없이 조용히 넘어간다
        productOptionAvailabilityService.updateSoldOut(999L, false);
    }
}
