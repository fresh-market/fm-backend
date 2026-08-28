package com.freshmarket.coupon;

// 다른 도메인이 보유 쿠폰의 상태를 옮길 때 쓰는 coupon 도메인의 공개 창구다.
public interface CouponApi {

    /**
     * 주문에서 쿠폰을 쓴다.
     *
     * <p>이미 사용된 것이면 아무 일도 안 하고 정상으로 끝난다. <b>그것은 실패가 아니라 늦게
     * 도착한 같은 요청</b>이라, 실패로 답하면 재시도한 호출자가 못 쓴 줄 알고 다시 시도한다.
     *
     * <p>{@code memberId} 를 함께 받는 이유는 소유권까지 이 메서드가 책임지기 위함이다.
     * 남의 발급분 번호를 넣으면 없는 것과 같은 실패가 된다.
     */
    void useCoupon(Long memberCouponId, Long memberId);

    /** 주문이 취소되어 사용을 철회한다. 이미 철회된 것이면 아무 일도 안 하고 정상으로 끝난다. */
    void cancelCouponUse(Long memberCouponId, Long memberId);
}
