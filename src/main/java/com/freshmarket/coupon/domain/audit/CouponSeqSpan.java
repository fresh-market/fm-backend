package com.freshmarket.coupon.domain.audit;

/**
 * 한정 쿠폰 한 장의 순번 범위다.
 *
 * @param maxSeq 지금까지 나간 가장 큰 순번
 * @param issued 실제로 존재하는 발급 행 수
 */
public record CouponSeqSpan(long couponId, int maxSeq, long issued) {

    /**
     * 순번의 구멍 수다. {@code docs/coupon/coupon.md} 3장의 식을 그대로 쓴다.
     *
     * <p>Redis 가 번호를 내줬는데 그 행이 안 들어간 만큼 벌어진다. 번호는 소모됐고 발급은 없다.
     *
     * @return 0 이면 연속이다
     */
    public long gap() {
        return maxSeq - issued;
    }

    public boolean hasGap() {
        return gap() != 0;
    }
}
