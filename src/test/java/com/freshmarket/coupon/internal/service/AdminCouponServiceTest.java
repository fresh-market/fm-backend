package com.freshmarket.coupon.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.coupon.internal.dto.AdminCouponListItem;
import com.freshmarket.coupon.internal.dto.AdminCouponListRow;
import com.freshmarket.coupon.internal.dto.AdminCouponSearchCondition;
import com.freshmarket.coupon.internal.entity.CouponScope;
import com.freshmarket.coupon.internal.entity.DiscountType;
import com.freshmarket.coupon.internal.repository.CouponQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * 쿼리가 맞는지는 DB가 있어야 알 수 있어 통합 시험이 따로 본다. 여기서는 페이지 자르기, 다음
 * 페이지 토큰 계산, 행에서 응답 항목으로의 매핑처럼 서비스가 스스로 하는 일만 본다.
 */
@ExtendWith(MockitoExtension.class)
class AdminCouponServiceTest {

    @Mock
    private CouponQueryRepository couponQueryRepository;

    @InjectMocks
    private AdminCouponService sut;

    @Test
    void 목록이_페이지_크기를_넘으면_다음_페이지_토큰을_만든다() {
        // given
        AdminCouponSearchCondition condition = new AdminCouponSearchCondition(null, null, null, 1);
        LocalDateTime now = LocalDateTime.now();
        when(couponQueryRepository.search(condition)).thenReturn(List.of(
                rowOf(2L, now),
                rowOf(1L, now.minusMinutes(1))));

        // when
        CursorPageResponse<AdminCouponListItem> result = sut.findAll(condition);

        // then
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).couponId()).isEqualTo(2L);
        assertThat(result.nextPageToken()).isNotNull();
    }

    @Test
    void 목록이_페이지_크기_안이면_다음_페이지_토큰이_없다() {
        // given
        AdminCouponSearchCondition condition = new AdminCouponSearchCondition(null, null, null, 20);
        when(couponQueryRepository.search(any())).thenReturn(List.of(rowOf(1L, LocalDateTime.now())));

        // when
        CursorPageResponse<AdminCouponListItem> result = sut.findAll(condition);

        // then
        assertThat(result.items()).hasSize(1);
        assertThat(result.nextPageToken()).isNull();
    }

    // 행의 값이 응답 항목에 그대로 옮겨지는지 확인한다
    @Test
    void 행의_값을_응답_항목에_그대로_옮긴다() {
        // given
        AdminCouponSearchCondition condition = new AdminCouponSearchCondition(null, null, null, 20);
        AdminCouponListRow row = new AdminCouponListRow(
                1L, "소비기한 임박 30% 할인", CouponScope.ITEM, DiscountType.RATE, 30, 10000, 20000,
                10000, 8231, LocalDateTime.of(2026, 8, 17, 11, 0), LocalDateTime.of(2026, 8, 17, 23, 59, 59),
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 20), null, true, LocalDateTime.now());
        when(couponQueryRepository.search(condition)).thenReturn(List.of(row));

        // when
        AdminCouponListItem item = sut.findAll(condition).items().get(0);

        // then
        assertThat(item.couponId()).isEqualTo(1L);
        assertThat(item.name()).isEqualTo("소비기한 임박 30% 할인");
        assertThat(item.scope()).isEqualTo(CouponScope.ITEM);
        assertThat(item.discountType()).isEqualTo(DiscountType.RATE);
        assertThat(item.discountValue()).isEqualTo(30);
        assertThat(item.maxDiscountAmount()).isEqualTo(10000);
        assertThat(item.minOrderAmount()).isEqualTo(20000);
        assertThat(item.totalQuantity()).isEqualTo(10000);
        assertThat(item.issuedQuantity()).isEqualTo(8231);
        assertThat(item.isActive()).isTrue();
    }

    private static AdminCouponListRow rowOf(long couponId, LocalDateTime createdAt) {
        return new AdminCouponListRow(
                couponId, "쿠폰", CouponScope.ORDER, DiscountType.AMOUNT, 1000, null, 0,
                null, 0, null, null, LocalDate.now(), LocalDate.now().plusDays(3), null, true, createdAt);
    }
}
