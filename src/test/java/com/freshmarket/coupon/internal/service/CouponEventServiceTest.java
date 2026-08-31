package com.freshmarket.coupon.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

import com.freshmarket.coupon.internal.entity.Coupon;
import com.freshmarket.coupon.internal.entity.CouponScope;
import com.freshmarket.coupon.internal.entity.DiscountType;
import com.freshmarket.coupon.internal.exception.CouponErrorCode;
import com.freshmarket.coupon.internal.exception.CouponException;
import com.freshmarket.coupon.internal.cache.CouponCache;
import com.freshmarket.coupon.internal.redis.CouponSeqInitializer;
import com.freshmarket.coupon.internal.repository.CouponRepository;
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

    /*
     * 마감 시각이 없으면 선착순 쿠폰이 아니다.
     * 끄는 조건도 키의 수명도 걸 기준이 없어, 열리면 네 키가 아무도 못 지우는 채로 남는다.
     */
    @Test
    void 마감_시각이_없으면_선착순이_아니라_열_수_없다() {
        // given
        givenCoupon(limitedCoupon(false, null));

        // when, then
        assertThatThrownBy(() -> sut.open(COUPON_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.NOT_LIMITED);
        verifyNoInteractions(seqInitializer);
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
    void 마감_전이면_관리자가_끌_수_없다() {
        // given
        givenCoupon(limitedCoupon(true, NOW.plusDays(1)));

        // when, then
        assertThatThrownBy(() -> sut.close(COUPON_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.EVENT_NOT_CLOSABLE);
        verify(couponRepository, never()).deactivateIfClosable(anyLong(), any(), any());
    }

    /*
     * 마감은 지났지만 대기가 안 끝났다.
     * 이때 끄면 아직 들어오는 플러시 때문에 발급 수를 잘못된 값으로 맞춘다.
     */
    @Test
    void 마감_직후에는_아직_끌_수_없다() {
        // given
        givenCoupon(limitedCoupon(true, NOW.minusSeconds(30)));

        // when, then
        assertThatThrownBy(() -> sut.close(COUPON_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.EVENT_NOT_CLOSABLE);
        verify(couponRepository, never()).deactivateIfClosable(anyLong(), any(), any());
    }

    /*
     * 소진으로는 못 끈다.
     * free 에 반납된 번호가 남아 있으면 스크립트가 다시 내주므로 소진이 최종이 아니고,
     * 스위치가 켜져 있어야 요청이 올 때 도는 회수가 묶인 번호를 되살린다.
     */
    @Test
    void 소진됐어도_마감_전이면_못_끈다() {
        // given
        givenCoupon(limitedCoupon(true, NOW.plusDays(1)));

        // when, then
        assertThatThrownBy(() -> sut.close(COUPON_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.EVENT_NOT_CLOSABLE);
    }

    // 끄기와 발급 수 맞추기와 키 치우기가 한 트랜잭션이다. 나누면 껐는데 안 맞은 행이 남는다
    @Test
    void 마감_대기가_끝나면_끄면서_발급_수를_맞추고_키를_치운다() {
        // given
        givenCoupon(limitedCoupon(true, NOW.minusMinutes(2)));
        when(couponRepository.deactivateIfClosable(eq(COUPON_ID), any(), any())).thenReturn(1);

        // when
        sut.close(COUPON_ID);

        // then
        verify(couponRepository).syncIssuedQuantity(COUPON_ID);
        verify(seqInitializer).clear(COUPON_ID);
    }

    @Test
    void 이미_꺼진_이벤트를_또_끄면_아무것도_하지_않는다() {
        // given
        givenCoupon(limitedCoupon(false, NOW.minusMinutes(2)));

        // when
        sut.close(COUPON_ID);

        // then
        verify(couponRepository, never()).deactivateIfClosable(anyLong(), any(), any());
    }

    /*
     * 확인과 갱신 사이에 남이 껐다. 결과가 같으므로 실패로 답하지 않는다.
     * 다만 이 호출이 끈 것은 아니므로 발급 수를 맞추거나 키를 치우지도 않아야 한다.
     */
    @Test
    void 끄는_사이에_남이_먼저_꺼도_실패로_답하지_않는다() {
        // given
        givenCoupon(limitedCoupon(true, NOW.minusMinutes(2)));
        when(couponRepository.deactivateIfClosable(eq(COUPON_ID), any(), any())).thenReturn(0);

        // when, then
        assertThatCode(() -> sut.close(COUPON_ID)).doesNotThrowAnyException();
        verify(couponRepository, never()).syncIssuedQuantity(anyLong());
        verifyNoInteractions(seqInitializer);
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

    // 대기가 끝난 이벤트만 후보다. 대기를 뺀 시각을 넘겨야 조건이 그것을 잰다
    @Test
    void 배치가_대기_끝난_이벤트를_찾는다() {
        // given
        when(couponRepository.findClosableEvents(NOW.minusSeconds(60))).thenReturn(List.of(COUPON_ID, 88L));

        // when, then
        assertThat(sut.findClosableEvents()).containsExactly(COUPON_ID, 88L);
    }

    // 배치도 관리자와 같은 순서를 지난다. 끄고, 맞추고, 치운다
    @Test
    void 배치가_끄면서_발급_수를_맞추고_키를_치운다() {
        // given
        when(couponRepository.deactivateIfClosable(eq(COUPON_ID), any(), any())).thenReturn(1);

        // when
        boolean closed = sut.closeIfDue(COUPON_ID);

        // then
        assertThat(closed).isTrue();
        verify(couponRepository).syncIssuedQuantity(COUPON_ID);
        verify(seqInitializer).clear(COUPON_ID);
    }

    /*
     * 남이 먼저 껐거나 아직 때가 아니다.
     * 발급 수를 맞추면 안 된다. 그 값이 아직 움직이는 중일 수 있다.
     */
    @Test
    void 못_끈_이벤트는_발급_수를_안_맞춘다() {
        // given
        when(couponRepository.deactivateIfClosable(eq(COUPON_ID), any(), any())).thenReturn(0);

        // when
        boolean closed = sut.closeIfDue(COUPON_ID);

        // then
        assertThat(closed).isFalse();
        verify(couponRepository, never()).syncIssuedQuantity(anyLong());
        verifyNoInteractions(seqInitializer);
    }

    private void givenCoupon(Coupon coupon) {
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
    }

    private static Coupon unlimitedCoupon() {
        Coupon coupon = Coupon.draftUnlimited("일반 쿠폰", CouponScope.ORDER, DiscountType.AMOUNT, 1000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2030, 1, 1),
                null, null, null);
        setField(coupon, "id", COUPON_ID);
        return coupon;
    }

    // 팩터리는 초안으로 만든다. 스위치는 사람이 켜는 값이라 생성 인자에 없어서 여기서 심는다
    private static Coupon limitedCoupon(boolean active, LocalDateTime issueEndAt) {
        Coupon coupon = Coupon.draftLimited("선착순 쿠폰", CouponScope.ORDER, DiscountType.AMOUNT, 1000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2030, 1, 1),
                TOTAL_QUANTITY, NOW.minusDays(1), issueEndAt,
                null, null, null);
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
