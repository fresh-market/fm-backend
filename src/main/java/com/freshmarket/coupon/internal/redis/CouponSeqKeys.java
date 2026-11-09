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
     * B 단계가 여기까지 훑었다고 남기는 표시다. 담는 값은 그때 살펴본 가장 큰 순번이다.
     *
     * 올라가기만 한다. 그래서 서버들이 어떤 순서로 들어와도 뒤에 온 대가 그 위만 보면 되고,
     * 합의도 기다림도 안 든다. 사라지면 다음 대가 전부 훑으므로 최악이 표시가 없던 때와 같다.
     */
    static String swept(long couponId) {
        return PREFIX + couponId + ":free-swept";
    }

    /*
     * 재건이 도는 동안 서 있는 발급 차단 표시다. 순번 확보가 이것을 보면 번호를 안 내준다.
     *
     * 차단이라 짧게 살고 아무도 안 푼다. 시간이 푼다. 푸는 이를 두면 먼저 끝낸 대가 아직
     * 하는 대의 차단까지 풀고, 세운 대가 죽으면 아무도 안 푼다.
     */
    static String raising(long couponId) {
        return PREFIX + couponId + ":raising";
    }

}
