package com.freshmarket.stock.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/*
 * 로트 소진율 계산. (입고 수량 - 현재 남은 재고) / 입고 수량.
 * 절대 판매량 기준은 신규 입고 상품을 저조로 오판하므로 쓰지 않는다.
 *
 * ExpiringSoonJudge 와 같은 이유로 internal.service/domain.batch 밖에 둔다 —
 * 순수 계산 로직을 배치/서비스 의존성 없이 단위 테스트로 직접 검증하기 위해서다.
 */
public final class TurnoverRateCalculator {

    private static final int SCALE = 4;

    private TurnoverRateCalculator() {
    }

    // 확보 재고(입고 수량)가 0이면 산출 대상이 아니다. 호출 전에 걸러야 한다
    public static BigDecimal calculate(int initialQty, int remainingQty) {
        if (initialQty <= 0) {
            throw new IllegalArgumentException("initialQty 는 0보다 커야 한다: " + initialQty);
        }
        if (remainingQty < 0 || remainingQty > initialQty) {
            throw new IllegalArgumentException(
                    "remainingQty 는 0 이상 initialQty 이하여야 한다: " + remainingQty + " (initialQty=" + initialQty + ")");
        }
        // int 뺄셈 결과를 그대로 valueOf(long) 에 넘기면 오버플로 가능성이 있어 long 으로 승격한다
        BigDecimal sold = BigDecimal.valueOf((long) initialQty - remainingQty);
        BigDecimal initial = BigDecimal.valueOf(initialQty);
        return sold.divide(initial, SCALE, RoundingMode.HALF_UP);
    }
}
