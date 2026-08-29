package com.freshmarket.coupon.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/*
 * 어긋남의 판정식만 따로 본다.
 * 무엇을 어긋남으로 셀 것인가는 SQL 이 아니라 이 셋이 정하므로, DB 없이 여기서 굳힌다.
 */
class CouponConsistencyFindingTest {

    @Test
    void 카운터가_실제_행_수와_다르면_어긋남이다() {
        CouponIssueCount counted = new CouponIssueCount(1L, 100, 1000, 97);

        assertThat(counted.counterMismatched()).isTrue();
        assertThat(counted.mismatched()).isTrue();
    }

    @Test
    void 한정_수량을_넘기면_어긋남이다() {
        CouponIssueCount counted = new CouponIssueCount(1L, 1001, 1000, 1001);

        assertThat(counted.exceedsTotal()).isTrue();
        assertThat(counted.mismatched()).isTrue();
    }

    /*
     * 과소는 어긋남이 아니다.
     * 이벤트가 안 끝났거나 다 안 팔린 것이라, 어긋남으로 세면 진행 중인 이벤트가 매번 걸린다.
     */
    @Test
    void 다_안_나간_것은_어긋남이_아니다() {
        CouponIssueCount counted = new CouponIssueCount(1L, 300, 1000, 300);

        assertThat(counted.mismatched()).isFalse();
        assertThat(counted.remaining()).isEqualTo(700);
    }

    // 무제한 쿠폰은 넘길 수량 자체가 없다
    @Test
    void 무제한_쿠폰은_수량_한도가_없다() {
        CouponIssueCount counted = new CouponIssueCount(1L, 50_000, null, 50_000);

        assertThat(counted.exceedsTotal()).isFalse();
        assertThat(counted.remaining()).isNull();
        assertThat(counted.mismatched()).isFalse();
    }

    // 번호는 나갔는데 행이 안 들어간 만큼 벌어진다. 구멍 수가 곧 어긋남이다
    @Test
    void 가장_큰_순번과_행_수의_차가_구멍이다() {
        CouponSeqSpan span = new CouponSeqSpan(1L, 10_000, 9_997);

        assertThat(span.gap()).isEqualTo(3);
        assertThat(span.hasGap()).isTrue();
    }

    @Test
    void 빠짐없이_들어갔으면_구멍이_없다() {
        CouponSeqSpan span = new CouponSeqSpan(1L, 10_000, 10_000);

        assertThat(span.hasGap()).isFalse();
    }

    /*
     * 행이 가장 큰 순번보다 많으면 음수가 된다.
     * 순번이 중복됐거나 0 이하가 들어간 것이라 이것도 어긋남이다. 0 이 아닌 것을 본다.
     */
    @Test
    void 행이_더_많아도_어긋남이다() {
        CouponSeqSpan span = new CouponSeqSpan(1L, 9_000, 9_001);

        assertThat(span.gap()).isEqualTo(-1);
        assertThat(span.hasGap()).isTrue();
    }

    /*
     * 이력 없는 발급분은 판정에서 뺀다.
     * 발급 경로가 이력을 안 남기는 것이 지금의 설계라, 넣으면 정상 발급 행이 전부 걸린다.
     */
    @Test
    void 이력_없는_발급분은_판정을_흐리지_않는다() {
        CouponConsistencyReport report =
                new CouponConsistencyReport(List.of(), List.of(), List.of(), 0, 3_000_000);

        assertThat(report.clean()).isTrue();
    }

    @Test
    void 마지막_전이가_어긋나면_깨끗하지_않다() {
        CouponConsistencyReport report =
                new CouponConsistencyReport(List.of(), List.of(), List.of(), 1, 0);

        assertThat(report.clean()).isFalse();
    }
}
