package com.freshmarket.product.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.product.internal.repository.ProductOptionRepository;
import java.time.LocalDateTime;
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
    void 조건부_UPDATE로_품절_여부를_갱신한다() {
        // given
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 24, 10, 0);
        when(productOptionRepository.updateSoldOutIfNewer(anyLong(), anyBoolean(), any())).thenReturn(1);

        // when
        productOptionAvailabilityService.updateSoldOut(31L, false, occurredAt);

        // then
        verify(productOptionRepository).updateSoldOutIfNewer(31L, false, occurredAt);
    }

    @Test
    void 이미_더_최신_값이_반영돼_있으면_예외를_던지지_않는다() {
        // given — 0건 갱신이지만 옵션 자체는 존재한다(더 최신 값이 이미 반영된 정상 케이스)
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 24, 10, 0);
        when(productOptionRepository.updateSoldOutIfNewer(anyLong(), anyBoolean(), any())).thenReturn(0);
        when(productOptionRepository.existsById(999L)).thenReturn(true);

        // when, then
        assertThatCode(() -> productOptionAvailabilityService.updateSoldOut(999L, false, occurredAt))
                .doesNotThrowAnyException();
    }

    @Test
    void 대상_옵션이_없으면_예외를_던진다() {
        // given — 0건 갱신이고 옵션 자체가 존재하지 않는다(데이터 정합성 문제)
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 24, 10, 0);
        when(productOptionRepository.updateSoldOutIfNewer(anyLong(), anyBoolean(), any())).thenReturn(0);
        when(productOptionRepository.existsById(404L)).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> productOptionAvailabilityService.updateSoldOut(404L, false, occurredAt))
                .isInstanceOf(IllegalStateException.class);
    }
}
