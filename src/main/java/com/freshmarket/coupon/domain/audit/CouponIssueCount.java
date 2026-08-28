package com.freshmarket.coupon.domain.audit;

/**
 * 쿠폰 한 장의 재고 세 값이다. 셋을 한 행으로 읽어야 서로 대조할 수 있다.
 *
 * @param issuedQuantity 쿠폰이 스스로 기억하는 발급 수
 * @param totalQuantity  한정 수량. 무제한 쿠폰은 {@code null}
 * @param actual         실제로 존재하는 발급 행 수
 */
public record CouponIssueCount(long couponId, long issuedQuantity, Integer totalQuantity, long actual) {

    /** 카운터가 실제 행 수와 다르다. 발급이 반영되다 끊겼거나 카운터 갱신이 빠졌다. */
    public boolean counterMismatched() {
        return issuedQuantity != actual;
    }

    /** 한정 수량을 넘겨 발급됐다. 선착순이 깨진 것이라 가장 무거운 어긋남이다. */
    public boolean exceedsTotal() {
        return totalQuantity != null && actual > totalQuantity;
    }

    /**
     * 아직 안 나간 수량이다.
     *
     * <p>이것은 <b>어긋남이 아니다.</b> 이벤트가 안 끝났거나 다 안 팔린 것이라 정상이다.
     * 어긋남으로 세면 진행 중인 이벤트가 매번 걸린다. 대신 값은 리포트에 남겨 읽을 수 있게 한다.
     *
     * @return 무제한 쿠폰은 {@code null}
     */
    public Long remaining() {
        return totalQuantity == null ? null : totalQuantity - actual;
    }

    public boolean mismatched() {
        return counterMismatched() || exceedsTotal();
    }
}
