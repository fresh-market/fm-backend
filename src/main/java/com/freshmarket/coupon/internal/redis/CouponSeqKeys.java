package com.freshmarket.coupon.internal.redis;

/**
 * 순번 확보가 쓰는 네 키의 이름이다. 넷은 함께 살고 함께 죽으므로 이름도 한곳에서 만든다
 * ({@code docs/coupon/coupon.md} 3장).
 */
final class CouponSeqKeys {

    private static final String PREFIX = "coupon:";

    private CouponSeqKeys() {
    }

    /** 회원 -> 순번. 값이 {@code "6"} 이면 번호만 받은 것이고 {@code "6:1"} 이면 커밋까지 끝난 것이다. */
    static String seq(long couponId) {
        return PREFIX + couponId + ":seq";
    }

    /** 반납된 순번. 낮은 것부터 다시 나간다. */
    static String free(long couponId) {
        return PREFIX + couponId + ":free";
    }

    /** 다음에 나갈 번호. */
    static String counter(long couponId) {
        return PREFIX + couponId + ":counter";
    }

    /** 아직 커밋 안 된 회원. 점수가 번호를 준 시각이다. */
    static String pending(long couponId) {
        return PREFIX + couponId + ":pending";
    }

    /*
     * 재건이 잡는 락이다. 넷과 함께 죽지 않으므로 위와 성격이 다르다.
     *
     * 이 락은 선출에만 쓴다. 여러 대가 같은 DB 읽기와 같은 계산을 중복하지 않게 하는 값이고
     * 발급을 막지 않는다 (docs/coupon/rebuild-v2/design.md 4.2).
     */
    static String rebuild(long couponId) {
        return PREFIX + couponId + ":rebuild";
    }

    /*
     * 재건이 도는 동안 서 있는 문이다. 순번 확보가 이것을 보면 번호를 안 내준다.
     *
     * 위 rebuild 락과 수명도 뜻도 다르다. 저쪽은 선출이라 30초를 살고 토큰이 맞는 대만 풀지만,
     * 이쪽은 장벽이라 짧게 살고 아무도 안 푼다. 시간이 연다.
     *
     * 한 키로 겸하면 선출이 끝날 때까지 문이 닫혀 있게 되고, 그것이 main 의 모양이다
     * (docs/coupon/rebuild-v3/design.md 5장).
     */
    static String raising(long couponId) {
        return PREFIX + couponId + ":raising";
    }

}
