package com.freshmarket.coupon.internal.redis;

/**
 * 재건이 문을 닫아 둘 수 있는 총 벽시계를 넘겼다.
 *
 * <p><b>이 예외는 재시도하지 않는다.</b> 다시 돌면 그 시도가 문을 또 닫아 발급 정지가 예산
 * 너머로 늘어난다. 예산을 둔 뜻이 바로 그것을 막는 것이다.
 *
 * <p>그만두면 그 쿠폰은 재건이 없던 때와 같은 상태로 남는다. 문은 시한이 열고, 뒤로 간 카운터가
 * 그대로 있으면 결번이 난다. <b>결번을 받아들이고 가용성을 지키는 선택이다</b>
 * ({@code docs/coupon/rebuild-gate-2026-10-31.md} 6장).
 */
class CouponSeqStallBudgetException extends RuntimeException {

    CouponSeqStallBudgetException(String message) {
        super(message);
    }
}
