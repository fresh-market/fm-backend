package com.freshmarket.product.domain.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.product.domain.repository.ProductOptionRepository;
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
        when(productOptionRepository.updateSoldOutIfNewer(31L, false, occurredAt)).thenReturn(1);

        // when
        productOptionAvailabilityService.updateSoldOut(31L, false, occurredAt);

        // then
        verify(productOptionRepository).updateSoldOutIfNewer(31L, false, occurredAt);
    }

    @Test
    void 대상은_있지만_이미_더_최신_값이_반영돼_있으면_예외를_던지지_않는다() {
        // given — (DI-2-01) 0건 갱신이지만 대상 옵션 자체는 존재한다 — 낡은 이벤트를 정상적으로 무시한 것
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 24, 10, 0);
        when(productOptionRepository.updateSoldOutIfNewer(31L, false, occurredAt)).thenReturn(0);
        when(productOptionRepository.existsById(31L)).thenReturn(true);

        // when, then
        assertThatCode(() -> productOptionAvailabilityService.updateSoldOut(31L, false, occurredAt))
                .doesNotThrowAnyException();
    }

    @Test
    void 대상_옵션이_없으면_예외를_던진다() {
        // given — (DI-2-01) 0건 갱신인데 대상 옵션 자체가 없다 — 진짜 갱신 실패라 재시도 큐로 보내야 한다
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 24, 10, 0);
        when(productOptionRepository.updateSoldOutIfNewer(999L, false, occurredAt)).thenReturn(0);
        when(productOptionRepository.existsById(999L)).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> productOptionAvailabilityService.updateSoldOut(999L, false, occurredAt))
                .isInstanceOf(IllegalStateException.class);
    }
}
