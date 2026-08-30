package com.freshmarket.coupon.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import com.freshmarket.coupon.domain.cache.CachedCoupon;
import com.freshmarket.coupon.domain.cache.CouponCache;
import com.freshmarket.coupon.domain.dto.CouponIssuanceStatusResponse;
import com.freshmarket.coupon.domain.entity.Coupon;
import com.freshmarket.coupon.domain.entity.CouponScope;
import com.freshmarket.coupon.domain.entity.DiscountType;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import com.freshmarket.coupon.domain.redis.CouponSeqAllocator;
import com.freshmarket.coupon.domain.repository.CouponRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class CouponIssuanceStatusServiceTest {

    private static final long COUPON_ID = 1L;

    @Mock
    private CouponCache couponCache;

    @Mock
    private CouponSeqAllocator seqAllocator;

    @Mock
    private CouponRepository couponRepository;

    @InjectMocks
    private CouponIssuanceStatusService sut;

    @Test
    void 쿠폰이_없으면_예외를_던진다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> sut.findStatus(COUPON_ID))
                .isInstanceOf(CouponException.class)
                .extracting(e -> ((CouponException) e).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    void 무제한_쿠폰이면_예외를_던진다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.of(unlimitedCoupon()));

        // when, then
        assertThatThrownBy(() -> sut.findStatus(COUPON_ID))
                .isInstanceOf(CouponException.class)
                .extracting(e -> ((CouponException) e).getErrorCode())
                .isEqualTo(CouponErrorCode.NOT_LIMITED);
    }

    @Test
    void 진행중인_이벤트는_레디스_카운터_값을_쓴다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.of(limitedCoupon(10000)));
        when(seqAllocator.currentIssuedCount(COUPON_ID)).thenReturn(Optional.of(8231));

        // when
        CouponIssuanceStatusResponse response = sut.findStatus(COUPON_ID);

        // then
        assertThat(response.totalQuantity()).isEqualTo(10000);
        assertThat(response.issuedQuantity()).isEqualTo(8231);
        assertThat(response.remaining()).isEqualTo(1769);
    }

    // 이벤트가 열린 적 없거나 이미 닫혀 카운터가 없으면 DB의 issued_quantity로 대신한다
    @Test
    void 레디스_카운터가_없으면_DB_값으로_대신한다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.of(limitedCoupon(10000)));
        when(seqAllocator.currentIssuedCount(COUPON_ID)).thenReturn(Optional.empty());
        Coupon coupon = Coupon.draftLimited("선착순 쿠폰", CouponScope.ORDER, DiscountType.AMOUNT, 1000,
                LocalDate.now(), LocalDate.now().plusDays(3),
                10000, LocalDateTime.now().minusDays(1), LocalDateTime.now().minusHours(1));
        setField(coupon, "issuedQuantity", 10000);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

        // when
        CouponIssuanceStatusResponse response = sut.findStatus(COUPON_ID);

        // then
        assertThat(response.issuedQuantity()).isEqualTo(10000);
        assertThat(response.remaining()).isZero();
    }

    // 카운터도 없고 DB에도 쿠폰이 없는 극단적인 경우까지 대비해 0으로 떨어지는지 본다
    @Test
    void DB에도_쿠폰이_없으면_발급수를_0으로_본다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.of(limitedCoupon(10000)));
        when(seqAllocator.currentIssuedCount(COUPON_ID)).thenReturn(Optional.empty());
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

        // when
        CouponIssuanceStatusResponse response = sut.findStatus(COUPON_ID);

        // then
        assertThat(response.issuedQuantity()).isZero();
        assertThat(response.remaining()).isEqualTo(10000);
    }

    // Redis 가 죽었어도 발급 경로는 멀쩡하므로 이 조회는 실패로 답하지 않고 DB 값으로 대신한다
    @Test
    void 레디스가_일시적으로_실패해도_DB_값으로_대신한다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.of(limitedCoupon(10000)));
        when(seqAllocator.currentIssuedCount(COUPON_ID))
                .thenThrow(new QueryTimeoutException("Redis 가 응답하지 않는다"));
        Coupon coupon = Coupon.draftLimited("선착순 쿠폰", CouponScope.ORDER, DiscountType.AMOUNT, 1000,
                LocalDate.now(), LocalDate.now().plusDays(3),
                10000, LocalDateTime.now().minusDays(1), LocalDateTime.now().minusHours(1));
        setField(coupon, "issuedQuantity", 8231);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

        // when
        CouponIssuanceStatusResponse response = sut.findStatus(COUPON_ID);

        // then
        assertThat(response.issuedQuantity()).isEqualTo(8231);
    }

    // 일시적이지 않은 실패(코드가 잘못 부른 경우 등)까지 덮으면 진짜 버그가 묻힌다
    @Test
    void 레디스_실패가_일시적이지_않으면_그대로_던진다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.of(limitedCoupon(10000)));
        when(seqAllocator.currentIssuedCount(COUPON_ID))
                .thenThrow(new InvalidDataAccessApiUsageException("잘못된 호출"));

        // when, then
        assertThatThrownBy(() -> sut.findStatus(COUPON_ID))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    private static CachedCoupon unlimitedCoupon() {
        return new CachedCoupon(COUPON_ID, CouponScope.ORDER, null, null, null, null, true);
    }

    private static CachedCoupon limitedCoupon(int totalQuantity) {
        return new CachedCoupon(COUPON_ID, CouponScope.ORDER, totalQuantity,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), null, true);
    }

    // CouponCacheTest, CouponEventServiceTest와 같은 이유다: 팩터리가 안 받는 필드(내부가 결정하는
    // 값)를 시험용으로만 강제로 채운다
    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
