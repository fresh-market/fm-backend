package com.freshmarket.coupon.internal.issue;

/**
 * 플러시 스레드가 배치를 쓴 뒤 카운터가 뒤처지지 않았는지 보는 자리다.
 *
 * <p>이 인터페이스가 있는 이유는 방향 때문이다. {@code issue} 패키지는 큐와 플러시를 갖고
 * {@code redis} 패키지가 그것을 읽어 재건한다. 플러시가 재건기를 직접 부르면 두 패키지가 서로를
 * 가리켜, <b>어느 쪽이 어느 쪽을 쓰는지가 흐려진다.</b>
 */
public interface CouponSeqRebuildSignal {

    /**
     * 방금 쓴 배치의 최대 순번이 카운터를 넘지 않았는지 잰다.
     *
     * <p><b>이제 재는 일만 한다.</b> 예전에는 같은 자리에서 재건 락을 보고 이 인스턴스의 큐를
     * 올리게 했는데, 승격으로 뒤로 간 카운터를 순번 확보가 그 자리에서 고치게 되어 올릴 일이
     * 없어졌다 ({@code docs/coupon/rebuild-rearchitecture-2026-10-12.md} 5.1).
     *
     * <p><b>부르는 쪽을 막지 않는다.</b> Redis 읽기 하나뿐이고 배치당 한 번이다.
     *
     * @param maxIssuedSeq 방금 쓴 배치에 든 가장 큰 순번. 카운터가 뒤처졌는지의 기준이 된다
     */
    void checkAfterFlush(long couponId, int maxIssuedSeq);

}
