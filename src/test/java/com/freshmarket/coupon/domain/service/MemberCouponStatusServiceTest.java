package com.freshmarket.coupon.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.repository.MemberCouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberCouponStatusServiceTest {

    private static final long MEMBER_COUPON_ID = 55L;
    private static final long MEMBER_ID = 5001L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 12, 0);
    private static final LocalDate TODAY = LocalDate.from(NOW);

    @Mock
    private MemberCouponRepository memberCouponRepository;

    private MemberCouponStatusService sut;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        sut = new MemberCouponStatusService(memberCouponRepository, fixed);
    }

    @Test
    void 사용하면_이력을_함께_남긴다() {
        // given
        when(memberCouponRepository.markUsed(MEMBER_COUPON_ID, MEMBER_ID, "ISSUED", TODAY, NOW)).thenReturn(1);

        // when
        sut.use(MEMBER_COUPON_ID, MEMBER_ID);

        // then
        verify(memberCouponRepository).recordTransition(MEMBER_COUPON_ID, "ISSUED", "USED", "주문에서 사용", NOW);
    }

    /*
     * 주문 취소로 돌려받은 쿠폰은 기간이 남았으면 다시 쓸 수 있다.
     * 이력의 from_status 가 CANCELED 여야 어디서 출발했는지가 남는다.
     */
    @Test
    void 철회한_쿠폰을_다시_쓰면_출발_상태가_이력에_남는다() {
        // given
        when(memberCouponRepository.markUsed(MEMBER_COUPON_ID, MEMBER_ID, "ISSUED", TODAY, NOW)).thenReturn(0);
        when(memberCouponRepository.markUsed(MEMBER_COUPON_ID, MEMBER_ID, "CANCELED", TODAY, NOW)).thenReturn(1);

        // when
        sut.use(MEMBER_COUPON_ID, MEMBER_ID);

        // then
        verify(memberCouponRepository).recordTransition(MEMBER_COUPON_ID, "CANCELED", "USED", "주문에서 사용", NOW);
    }

    /*
     * 만료 배치는 하루에 한 번 돌아서 기간이 지난 발급분이 한동안 ISSUED 로 남는다.
     * 그 창에서도 쓸 수 없어야 한다.
     */
    @Test
    void 기간이_지났으면_표시가_ISSUED_여도_못_쓴다() {
        // given
        when(memberCouponRepository.markUsed(anyLong(), anyLong(), anyString(), any(), any())).thenReturn(0);
        when(memberCouponRepository.findStatus(MEMBER_COUPON_ID, MEMBER_ID)).thenReturn(List.of("ISSUED"));
        when(memberCouponRepository.countWithinValidPeriod(MEMBER_COUPON_ID, TODAY)).thenReturn(0);

        // when, then
        assertThatThrownBy(() -> sut.use(MEMBER_COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.NOT_USABLE_PERIOD);
    }

    /*
     * 사용한 뒤에 기간이 지난 쿠폰에서 같은 요청이 재시도되는 경우다.
     * 기간을 먼저 보면 이미 반영된 요청이 기간 오류를 받는다.
     */
    @Test
    void 이미_썼으면_기간이_지났어도_성공으로_답한다() {
        // given
        when(memberCouponRepository.markUsed(anyLong(), anyLong(), anyString(), any(), any())).thenReturn(0);
        when(memberCouponRepository.findStatus(MEMBER_COUPON_ID, MEMBER_ID)).thenReturn(List.of("USED"));

        // when, then
        sut.use(MEMBER_COUPON_ID, MEMBER_ID);
        verify(memberCouponRepository, never()).countWithinValidPeriod(anyLong(), any());
    }

    /*
     * 요구사항이 "반복해서, 또는 동시에 발생해도 한 번만 반영" 을 요구한다.
     * 두 번째 요청은 0행이 되고, 이미 그 상태라면 실패가 아니라 늦게 도착한 같은 요청이다.
     */
    @Test
    void 이미_사용된_것이면_조용히_끝난다() {
        // given
        when(memberCouponRepository.markUsed(anyLong(), anyLong(), anyString(), any(), any())).thenReturn(0);
        when(memberCouponRepository.findStatus(MEMBER_COUPON_ID, MEMBER_ID)).thenReturn(List.of("USED"));

        // when, then
        sut.use(MEMBER_COUPON_ID, MEMBER_ID);
        verify(memberCouponRepository, never()).recordTransition(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void 만료로_표시된_것은_쓸_수_없다() {
        // given
        when(memberCouponRepository.markUsed(anyLong(), anyLong(), anyString(), any(), any())).thenReturn(0);
        when(memberCouponRepository.findStatus(MEMBER_COUPON_ID, MEMBER_ID)).thenReturn(List.of("EXPIRED"));
        when(memberCouponRepository.countWithinValidPeriod(MEMBER_COUPON_ID, TODAY)).thenReturn(1);

        // when, then
        assertThatThrownBy(() -> sut.use(MEMBER_COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.INVALID_STATUS_TRANSITION);
    }

    // 남의 발급분과 없는 발급분을 가르지 않는다. 가르면 번호를 훑어 남의 쿠폰의 존재를 알아낼 수 있다
    @Test
    void 남의_것이거나_없으면_찾을_수_없다고_답한다() {
        // given
        when(memberCouponRepository.markUsed(anyLong(), anyLong(), anyString(), any(), any())).thenReturn(0);
        when(memberCouponRepository.findStatus(MEMBER_COUPON_ID, MEMBER_ID)).thenReturn(List.of());

        // when, then
        assertThatThrownBy(() -> sut.use(MEMBER_COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.MEMBER_COUPON_NOT_FOUND);
    }

    @Test
    void 사용을_철회하면_이력을_함께_남긴다() {
        // given
        when(memberCouponRepository.markCanceled(MEMBER_COUPON_ID, MEMBER_ID, NOW)).thenReturn(1);

        // when
        sut.cancelUse(MEMBER_COUPON_ID, MEMBER_ID);

        // then
        verify(memberCouponRepository).recordTransition(MEMBER_COUPON_ID, "USED", "CANCELED", "주문 취소", NOW);
    }

    @Test
    void 이미_철회된_것이면_조용히_끝난다() {
        // given
        when(memberCouponRepository.markCanceled(anyLong(), anyLong(), any())).thenReturn(0);
        when(memberCouponRepository.findStatus(MEMBER_COUPON_ID, MEMBER_ID)).thenReturn(List.of("CANCELED"));

        // when, then
        sut.cancelUse(MEMBER_COUPON_ID, MEMBER_ID);
        verify(memberCouponRepository, never()).recordTransition(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void 만료할_것이_없으면_0을_돌려준다() {
        // given
        when(memberCouponRepository.findExpirable(any(), anyInt())).thenReturn(List.of());

        // when, then
        assertThat(sut.expireOverdueChunk()).isZero();
        verify(memberCouponRepository, never()).markExpired(any(), any(), any());
    }

    @Test
    void 만료하면_이력을_함께_남긴다() {
        // given
        List<Long> ids = List.of(1L, 2L, 3L);
        when(memberCouponRepository.findExpirable(any(), anyInt())).thenReturn(ids);
        when(memberCouponRepository.markExpired(ids, LocalDate.from(NOW), NOW)).thenReturn(3);

        // when
        int expired = sut.expireOverdueChunk();

        // then
        assertThat(expired).isEqualTo(3);
        verify(memberCouponRepository).recordExpiredTransitions(ids, "유효기간 도래", NOW);
    }

    /*
     * 고른 뒤 갱신하기까지 사이에 사용 요청이 끼어들면 그 행은 더 이상 ISSUED 가 아니다.
     * 그때는 사용 쪽이 이기고 만료가 아무것도 못 바꾼다. 이력도 남기면 안 된다.
     */
    @Test
    void 고른_사이에_다_사용됐으면_이력을_남기지_않는다() {
        // given
        List<Long> ids = List.of(1L, 2L);
        when(memberCouponRepository.findExpirable(any(), anyInt())).thenReturn(ids);
        when(memberCouponRepository.markExpired(any(), any(), any())).thenReturn(0);

        // when
        int expired = sut.expireOverdueChunk();

        // then
        assertThat(expired).isZero();
        verify(memberCouponRepository, never()).recordExpiredTransitions(any(), anyString(), any());
    }
}
