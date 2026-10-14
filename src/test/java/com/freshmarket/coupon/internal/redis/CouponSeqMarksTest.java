package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 순번 기억의 계약이다. 이 값이 재건에서 카운터의 하한이라 <b>낮아지면 안 되고 이벤트를 넘겨
 * 남아서도 안 된다.</b>
 */
@DisplayName("순번 기억")
class CouponSeqMarksTest {

    private static final long COUPON_ID = 900_001L;

    private final CouponSeqMarks sut = new CouponSeqMarks();

    // 아무것도 안 본 쿠폰은 0 이다. 재건이 0 을 "견줄 것이 없다" 로 받는다
    @Test
    void 처음에는_0_이다() {
        assertThat(sut.of(COUPON_ID)).isZero();
    }

    // 올리기만 하는 값이어야 한다. 낮아지면 이미 내준 번호를 다시 내주게 된다
    @Test
    void 낮은_번호를_봐도_안_내려간다() {
        sut.saw(COUPON_ID, 500);
        sut.saw(COUPON_ID, 7);

        assertThat(sut.of(COUPON_ID)).isEqualTo(500);
    }

    @Test
    void 높은_번호를_보면_올라간다() {
        sut.saw(COUPON_ID, 500);
        sut.saw(COUPON_ID, 501);

        assertThat(sut.of(COUPON_ID)).isEqualTo(501);
    }

    /*
     * 이 시험이 설계의 가장 위험한 자리를 막는다.
     *
     * 준비 단계가 카운터를 0 으로 세우므로 1 은 갓 열린 이벤트의 첫 번호다. 지난 이벤트의
     * 최댓값을 들고 있으면 재건이 그 값까지 카운터를 올려 그만큼의 재고를 통째로 버린다.
     * 만 장짜리에서 기억이 5,887 이면 4,113장만 나간다.
     */
    @Test
    void 번호_1_을_보면_지난_이벤트의_기억을_버린다() {
        sut.saw(COUPON_ID, 5_887);

        sut.saw(COUPON_ID, 1);

        assertThat(sut.of(COUPON_ID)).isEqualTo(1);
    }

    // 쿠폰마다 따로 센다. 섞이면 한 이벤트의 최댓값이 다른 이벤트의 재고를 깎는다
    @Test
    void 쿠폰을_섞지_않는다() {
        sut.saw(COUPON_ID, 500);
        sut.saw(COUPON_ID + 1, 3);

        assertThat(sut.of(COUPON_ID)).isEqualTo(500);
        assertThat(sut.of(COUPON_ID + 1)).isEqualTo(3);
    }

    // 이벤트가 끝나 키가 사라지면 버린다. 안 버리면 쿠폰 수만큼 항목이 쌓인다
    @Test
    void 버리면_0_으로_돌아간다() {
        sut.saw(COUPON_ID, 500);

        sut.forget(COUPON_ID);

        assertThat(sut.of(COUPON_ID)).isZero();
    }
}
