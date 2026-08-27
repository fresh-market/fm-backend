package com.freshmarket.coupon.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.freshmarket.coupon.domain.entity.Coupon;
import com.freshmarket.coupon.domain.entity.CouponScope;
import com.freshmarket.coupon.domain.entity.DiscountType;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import com.freshmarket.coupon.domain.cache.CouponCache;
import com.freshmarket.coupon.domain.redis.CouponSeqInitializer;
import com.freshmarket.coupon.domain.repository.CouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponEventServiceTest {

    private static final long COUPON_ID = 77L;
    private static final int TOTAL_QUANTITY = 100;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 12, 0);

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private CouponSeqInitializer seqInitializer;

    @Mock
    private CouponCache couponCache;

    private CouponEventService sut;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        sut = new CouponEventService(couponRepository, seqInitializer, couponCache, fixed);
    }

    @Test
    void 없는_쿠폰은_열_수_없다() {
        // given
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> sut.open(COUPON_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.COUPON_NOT_FOUND);
        verifyNoInteractions(seqInitializer);
    }

    @Test
    void 무제한_쿠폰은_선착순_이벤트가_아니다() {
        // given
        givenCoupon(unlimitedCoupon());

        // when, then
        assertThatThrownBy(() -> sut.open(COUPON_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.NOT_LIMITED);
        verifyNoInteractions(seqInitializer);
    }

    /*
     * 카운터가 먼저 서야 한다.
     * 스위치가 먼저 켜지면 그 틈에 들어온 요청이 카운터 없는 Redis 를 쳐서, 재고가 멀쩡한데
     * 혼잡 응답을 받는다. 트랜잭션이 그 갱신을 커밋 전까지 감춰 순서를 지킨다.
     */
    @Test
    void 이벤트를_열면_카운터를_세우고_스위치를_켠다() {
        // given
        givenCoupon(limitedCoupon(false, NOW.plusDays(1)));
        when(couponRepository.activateIfInactive(eq(COUPON_ID), any())).thenReturn(1);

        // when
        sut.open(COUPON_ID);

        // then
        verify(seqInitializer).prepare(COUPON_ID, NOW.plusDays(1));
    }

    // 남이 이미 열었다. 여기서 Redis 를 다시 세우면 도는 이벤트의 카운터를 지운다
    @Test
    void 이미_열린_이벤트는_카운터를_다시_세우지_않는다() {
        // given
        givenCoupon(limitedCoupon(true, NOW.plusDays(1)));
        when(couponRepository.activateIfInactive(eq(COUPON_ID), any())).thenReturn(0);

        // when
        sut.open(COUPON_ID);

        // then
        verifyNoInteractions(seqInitializer);
    }

    @Test
    void 소진_전이고_마감_전이면_관리자가_끌_수_없다() {
        // given
        givenCoupon(limitedCoupon(true, NOW.plusDays(1)));
        when(couponRepository.countIssued(COUPON_ID)).thenReturn(TOTAL_QUANTITY - 1);

        // when, then
        assertThatThrownBy(() -> sut.close(COUPON_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.EVENT_NOT_CLOSABLE);
        verify(couponRepository, never()).deactivateIfClosable(anyLong(), any());
    }

    // 행이 총량만큼 실재하면 스크립트가 회수할 번호가 없어 최종이다
    @Test
    void 소진됐으면_마감_전이라도_끌_수_있다() {
        // given
        givenCoupon(limitedCoupon(true, NOW.plusDays(1)));
        when(couponRepository.countIssued(COUPON_ID)).thenReturn(TOTAL_QUANTITY);
        when(couponRepository.deactivateIfClosable(eq(COUPON_ID), any())).thenReturn(1);

        // when
        sut.close(COUPON_ID);

        // then
        verify(couponRepository).deactivateIfClosable(eq(COUPON_ID), any());
    }

    @Test
    void 마감_시각이_지났으면_소진_전이라도_끌_수_있다() {
        // given
        givenCoupon(limitedCoupon(true, NOW.minusMinutes(1)));
        when(couponRepository.deactivateIfClosable(eq(COUPON_ID), any())).thenReturn(1);

        // when
        sut.close(COUPON_ID);

        // then
        verify(couponRepository).deactivateIfClosable(eq(COUPON_ID), any());
        // 마감으로 이미 판정났으므로 굳이 세지 않는다
        verify(couponRepository, never()).countIssued(anyLong());
    }

    @Test
    void 이미_꺼진_이벤트를_또_끄면_아무것도_하지_않는다() {
        // given
        givenCoupon(limitedCoupon(false, NOW.minusMinutes(1)));

        // when
        sut.close(COUPON_ID);

        // then
        verify(couponRepository, never()).deactivateIfClosable(anyLong(), any());
    }

    // 확인과 갱신 사이에 남이 껐다. 결과가 같으므로 실패로 답하지 않는다
    @Test
    void 끄는_사이에_남이_먼저_꺼도_실패로_답하지_않는다() {
        // given
        givenCoupon(limitedCoupon(true, NOW.minusMinutes(1)));
        when(couponRepository.deactivateIfClosable(eq(COUPON_ID), any())).thenReturn(0);

        // when, then
        sut.close(COUPON_ID);
    }

    @Test
    void 이미_시작한_이벤트의_발급_시각은_바꿀_수_없다() {
        // given
        givenCoupon(limitedCoupon(true, NOW.plusDays(1)));
        when(couponRepository.updateIssuePeriodIfNotStarted(anyLong(), any(), any(), any())).thenReturn(0);

        // when, then
        assertThatThrownBy(() -> sut.changeIssuePeriod(COUPON_ID, NOW.plusDays(2), NOW.plusDays(3)))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.ISSUE_PERIOD_LOCKED);
        verifyNoInteractions(seqInitializer);
    }

    /*
     * 옛 마감으로 계산된 TTL 이 남아 있으면 키가 새 마감보다 먼저 사라진다.
     * 켜져 있는 이벤트에서만 카운터가 있으므로 그때만 다시 건다.
     */
    @Test
    void 켜진_이벤트의_시각을_바꾸면_TTL_을_다시_건다() {
        // given
        givenCoupon(limitedCoupon(true, NOW.plusDays(1)));
        when(couponRepository.updateIssuePeriodIfNotStarted(anyLong(), any(), any(), any())).thenReturn(1);

        // when
        sut.changeIssuePeriod(COUPON_ID, NOW.plusDays(2), NOW.plusDays(3));

        // then
        verify(seqInitializer).applyTtl(COUPON_ID, NOW.plusDays(3));
    }

    @Test
    void 꺼진_이벤트의_시각을_바꾸면_TTL_을_안_건다() {
        // given
        givenCoupon(limitedCoupon(false, NOW.plusDays(1)));
        when(couponRepository.updateIssuePeriodIfNotStarted(anyLong(), any(), any(), any())).thenReturn(1);

        // when
        sut.changeIssuePeriod(COUPON_ID, NOW.plusDays(2), NOW.plusDays(3));

        // then
        verifyNoInteractions(seqInitializer);
    }

    @Test
    void 배치가_마감된_이벤트를_끈다() {
        // given
        when(couponRepository.deactivateFinishedEvents(any())).thenReturn(2);

        // when
        int closed = sut.closeFinishedEvents();

        // then
        assertThat(closed).isEqualTo(2);
    }

    @Test
    void 끌_이벤트가_없으면_아무_일도_없다() {
        // given
        when(couponRepository.deactivateFinishedEvents(any())).thenReturn(0);

        // when
        int closed = sut.closeFinishedEvents();

        // then
        assertThat(closed).isZero();
    }

    // 배치가 발급 수를 먼저 맞추고 나서 키를 지운다. 순서가 뒤집히면 셀 대상을 잃는다
    @Test
    void 배치가_발급_수를_맞추고_키를_치운다() {
        // given
        when(couponRepository.findCleanupTargets(any(), any())).thenReturn(List.of(COUPON_ID, 88L));

        // when
        int cleaned = sut.cleanupClosedEvents();

        // then
        assertThat(cleaned).isEqualTo(2);
        verify(couponRepository).syncIssuedQuantity(COUPON_ID);
        verify(seqInitializer).clear(COUPON_ID);
        verify(couponRepository).syncIssuedQuantity(88L);
        verify(seqInitializer).clear(88L);
    }

    private void givenCoupon(Coupon coupon) {
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
    }

    private static Coupon unlimitedCoupon() {
        Coupon coupon = Coupon.draftUnlimited("일반 쿠폰", CouponScope.ORDER, DiscountType.AMOUNT, 1000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2030, 1, 1));
        setField(coupon, "id", COUPON_ID);
        return coupon;
    }

    // 팩터리는 초안으로 만든다. 스위치는 사람이 켜는 값이라 생성 인자에 없어서 여기서 심는다
    private static Coupon limitedCoupon(boolean active, LocalDateTime issueEndAt) {
        Coupon coupon = Coupon.draftLimited("선착순 쿠폰", CouponScope.ORDER, DiscountType.AMOUNT, 1000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2030, 1, 1),
                TOTAL_QUANTITY, NOW.minusDays(1), issueEndAt);
        setField(coupon, "id", COUPON_ID);
        setField(coupon, "active", active);
        return coupon;
    }

    private static void setField(Object target, String name, Object value) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                // 상위 클래스에 있을 수 있다. BaseMutableTimeEntity 가 id 를 갖는다
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(name + " 을 심지 못했다", e);
            }
        }
        throw new IllegalStateException(name + " 필드가 없다");
    }
}
