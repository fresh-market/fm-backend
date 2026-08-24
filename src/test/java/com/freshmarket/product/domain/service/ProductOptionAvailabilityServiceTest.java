package com.freshmarket.product.domain.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;

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

        // when
        productOptionAvailabilityService.updateSoldOut(31L, false, occurredAt);

        // then
        verify(productOptionRepository).updateSoldOutIfNewer(31L, false, occurredAt);
    }

    @Test
    void 대상이_없거나_이미_더_최신_값이_반영돼_있어도_예외를_던지지_않는다() {
        // given — repository는 0건 갱신을 반환할 뿐 예외를 던지지 않는다(대상 없음/더 최신 값 존재 둘 다)
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 24, 10, 0);

        // when, then
        assertThatCode(() -> productOptionAvailabilityService.updateSoldOut(999L, false, occurredAt))
                .doesNotThrowAnyException();
    }
}
