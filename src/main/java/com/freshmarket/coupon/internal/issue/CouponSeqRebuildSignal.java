package com.freshmarket.coupon.internal.issue;

/**
 * 플러시 스레드가 배치를 쓴 뒤 재건이 도는지 보는 자리다.
 *
 * <p>이 인터페이스가 있는 이유는 방향 때문이다. {@code issue} 패키지는 큐와 플러시를 갖고
 * {@code redis} 패키지가 그것을 읽어 재건한다. 플러시가 재건기를 직접 부르면 두 패키지가 서로를
 * 가리켜, <b>어느 쪽이 어느 쪽을 쓰는지가 흐려진다.</b>
 */
public interface CouponSeqRebuildSignal {

    /**
     * 방금 쓴 배치의 쿠폰이 재건 중인지 보고, 그렇다면 이 인스턴스의 큐를 올리게 한다.
     *
     * <p><b>부르는 쪽을 막지 않는다.</b> 실제 일은 다른 스레드가 한다. 여기서 큐를 얼리면
     * 플러시 스레드가 자기를 얼리는 셈이 된다.
     */
    void checkAfterFlush(long couponId);
}
