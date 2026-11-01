package com.freshmarket.coupon.internal.redis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 이 인스턴스가 쿠폰마다 받아 본 가장 큰 순번이다. 순번 확보가 쓰고 재건이 읽는다.
 *
 * <p><b>이 값이 재건에서 세는 일을 없앤 열쇠다.</b> 카운터는 올리기만 하는 값이라 전체 최댓값이
 * 각 인스턴스가 아는 최댓값들의 최댓값이다. 그러면 각자 자기 것만 올려도 결과가 수렴하므로,
 * <b>주도자가 몇 대를 기다릴지 셀 필요가 없다.</b> 세는 데 실패하는 경우를 고칠 때마다 구멍이
 * 자리를 옮긴 것이 이 설계를 갈아 치운 이유다
 * ({@code docs/coupon/rebuild-v2/design.md} 3장).
 *
 * <p><b>JVM 안에만 둔다.</b> Redis 에 두면 재건을 일으킨 그 장애가 이 값도 함께 지운다. 명부를
 * Redis 에 두었던 때가 그랬다. DB 에 두면 발급마다 쓰기가 늘고 이 값은 그만큼의 값어치가 없다.
 * 인스턴스가 죽으면 그 큐의 티켓도 함께 사라지므로 <b>기억이 함께 사라지는 것이 의미상 맞다.</b>
 *
 * <p><b>번호 1 을 보면 기억을 버린다.</b> 준비 단계가 카운터를 0 으로 세우므로 1 은 갓 열린
 * 이벤트의 첫 번호이고, 그 뒤로는 이벤트가 도는 동안 다시 나오지 않는다. 안 버리면 같은 쿠폰을
 * 다시 열었을 때 지난 이벤트의 최댓값이 남아, 재건이 그 값까지 카운터를 올려 <b>그만큼의 재고를
 * 통째로 버린다.</b> 만 장짜리에서 기억이 5,887 이면 4,113장만 나간다.
 */
@Component
class CouponSeqMarks {

    private final Map<Long, Integer> marks = new ConcurrentHashMap<>();

    /** 아직 아무 번호도 안 본 쿠폰은 0 이다. 재건이 0 을 "견줄 것이 없다" 로 받는다. */
    int of(long couponId) {
        return marks.getOrDefault(couponId, 0);
    }

    /**
     * 받은 번호 하나를 기억에 넣는다. 순번 확보가 번호를 돌려줄 때마다 부른다.
     *
     * <p>이미 발급된 회원이 재시도로 같은 번호를 다시 받은 경우도 넣는다. 그 번호도 이 인스턴스가
     * 내준 것이라 지켜야 할 대상이고, 최댓값이라 두 번 넣어도 값이 안 바뀐다.
     */
    void saw(long couponId, int seq) {
        if (seq == 1) {
            marks.put(couponId, 1);
            return;
        }
        marks.merge(couponId, seq, Math::max);
    }

    /** 이벤트가 끝나 키가 사라지면 버린다. 안 버리면 쿠폰 수만큼 항목이 쌓인다. */
    void forget(long couponId) {
        marks.remove(couponId);
    }
}
