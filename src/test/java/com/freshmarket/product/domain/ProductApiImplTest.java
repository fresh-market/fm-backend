package com.freshmarket.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.freshmarket.product.domain.repository.ProductOptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ProductApiImpl 이 다른 도메인에 정확한 정보를 돌려주는지 검증한다
@ExtendWith(MockitoExtension.class)
class ProductApiImplTest {

    @Mock
    private ProductOptionRepository productOptionRepository;

    @InjectMocks
    private ProductApiImpl productApiImpl;

    @Test
    void 존재하는_옵션이면_상품_소속을_확인한다() {
        // given
        when(productOptionRepository.existsByIdAndProductId(31L, 12L)).thenReturn(true);

        // when
        boolean result = productApiImpl.existsOption(12L, 31L);

        // then
        assertThat(result).isTrue();
    }

    @Test
    void 존재하지_않는_옵션이면_false를_준다() {
        // given
        when(productOptionRepository.existsByIdAndProductId(999L, 12L)).thenReturn(false);

        // when
        boolean result = productApiImpl.existsOption(12L, 999L);

        // then
        assertThat(result).isFalse();
    }
}