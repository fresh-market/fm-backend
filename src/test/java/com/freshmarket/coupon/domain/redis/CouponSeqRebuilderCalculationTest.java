package com.freshmarket.coupon.domain.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.freshmarket.coupon.domain.repository.MemberCouponSeqRepository.IssuedSeq;
import org.junit.jupiter.api.Test;

/*
 * 재건이 세울 값을 정하는 두 식을 본다. Redis 도 DB 도 필요 없는 순수 계산이다.
 *
 * 이 둘이 이 기능의 정확성 전부를 정한다. 카운터가 낮으면 이미 행이 있는 번호가 다시 나가고,
 * 높으면 상한 밖 번호가 나간다. 구멍을 덜 잡으면 그만큼 재고가 덜 팔린다.
 */
class CouponSeqRebuilderCalculationTest {

    @Test
    void 카운터는_DB_와_큐_중_더_큰_쪽이다() {
        List<IssuedSeq> issued = List.of(new IssuedSeq(9101, 1), new IssuedSeq(9102, 3));
        Map<Long, Integer> queued = Map.of(9103L, 5);

        assertThat(CouponSeqRebuilder.maxSeq(issued, queued)).isEqualTo(5);
    }

    // 큐가 반납된 낮은 번호를 쥐고 있을 수 있다. 그때는 DB 쪽이 더 크다
    @Test
    void 큐가_낮은_번호를_쥐고_있으면_DB_가_이긴다() {
        List<IssuedSeq> issued = List.of(new IssuedSeq(9101, 10));
        Map<Long, Integer> queued = Map.of(9103L, 3);

        assertThat(CouponSeqRebuilder.maxSeq(issued, queued)).isEqualTo(10);
    }

    /*
     * 개수로 더하면 안 되는 이유가 이 시험이다.
     * 회수는 소진 시점에 도므로 큐가 낮은 번호를 쥐는 것이 평범한 마무리 국면이다. 그때 더하면
     * 카운터가 총량을 지나가고, free 경로는 상한 검사를 안 지나 반드시 실패할 번호가 나간다.
     */
    @Test
    void 개수로_더하면_총량을_넘지만_최댓값은_안_넘는다() {
        int 총량 = 10;
        // 소진됐고, 회수로 되살아난 3 번과 4 번을 큐가 쥐고 있다
        List<IssuedSeq> issued = List.of(new IssuedSeq(9101, 총량));
        Map<Long, Integer> queued = Map.of(9103L, 3, 9104L, 4);

        int 개수로_더한_값 = 총량 + queued.size();

        assertThat(개수로_더한_값).isGreaterThan(총량);
        assertThat(CouponSeqRebuilder.maxSeq(issued, queued)).isEqualTo(총량);
    }

    @Test
    void 발급도_큐도_없으면_0_이다() {
        assertThat(CouponSeqRebuilder.maxSeq(List.of(), Map.of())).isZero();
    }

    @Test
    void 구멍은_아무도_안_쥔_번호다() {
        List<IssuedSeq> issued = List.of(new IssuedSeq(9101, 1), new IssuedSeq(9102, 4));
        Map<Long, Integer> queued = Map.of(9103L, 5);

        // 2 와 3 은 주인이 없다. 5 는 큐가 쥐고 있어 빠진다
        assertThat(CouponSeqRebuilder.gaps(issued, queued, 5)).containsExactly(2, 3);
    }

    // MAX 와 최댓값 사이도 훑는다. 그 구간에도 나갔다가 주인을 잃은 번호가 있다
    @Test
    void DB_최댓값_위쪽도_훑는다() {
        List<IssuedSeq> issued = List.of(new IssuedSeq(9101, 1));

        assertThat(CouponSeqRebuilder.gaps(issued, Map.of(), 4)).containsExactly(2, 3, 4);
    }

    @Test
    void 빈틈이_없으면_되살릴_것이_없다() {
        List<IssuedSeq> issued = List.of(new IssuedSeq(9101, 1), new IssuedSeq(9102, 2));

        assertThat(CouponSeqRebuilder.gaps(issued, Map.of(), 2)).isEmpty();
    }
}
