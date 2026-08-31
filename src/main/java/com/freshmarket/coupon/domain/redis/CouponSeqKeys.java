package com.freshmarket.coupon.domain.redis;

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
     * 재건은 넷이 없을 때 도는 일이라 그 수명을 물려받을 대상이 없고, 자기 TTL 로 산다.
     */
    static String rebuild(long couponId) {
        return PREFIX + couponId + ":rebuild";
    }

    /*
     * 재건이 도는 동안 각 인스턴스가 자기 큐를 올려 두는 자리다. 회원 -> 순번이다.
     * 회원 하나의 티켓은 한 인스턴스에만 있으므로 여럿이 같은 해시에 써도 겹치지 않는다.
     * 재건이 끝나면 지운다.
     */
    static String rebuildQueued(long couponId) {
        return PREFIX + couponId + ":rebuild:queued";
    }
}
