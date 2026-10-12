package com.freshmarket.coupon.internal.redis;

/**
 * 순번 확보의 결과. 네 갈래가 그대로 네 가지 응답이 된다({@code docs/coupon/coupon.md} 3장).
 *
 * <p>봉인 계층으로 둔 이유가 둘이다. 하나는 호출부가 {@code switch} 로 받을 때 빠뜨린 갈래를
 * 컴파일러가 잡아 주는 것이고, 다른 하나는 순번이 있는 갈래에만 {@code seq} 를 두어 없는 자리에서
 * {@code null} 을 만질 수 없게 하는 것이다.
 */
public sealed interface SeqOutcome {

    /** 이 번호로 발급을 진행한다. 새로 받았든 재시도로 같은 번호를 다시 받았든 할 일은 같다. */
    record Allocated(int seq) implements SeqOutcome {
    }

    /** 커밋까지 끝난 회원이다. DB 를 안 치고 그대로 답한다. */
    record AlreadyIssued(int seq) implements SeqOutcome {
    }

    /**
     * 줄 번호가 없다. 다만 미확정 순번을 쥔 사람이 있어 <b>기준 시간이 지나면 그 번호가 다시
     * 나온다.</b> 그래서 최종이 아니고 409 로 답한다.
     */
    record SoldOut() implements SeqOutcome {
    }

    /**
     * 줄 번호가 없고 <b>미확정 순번을 쥔 사람도 없다.</b> 회수도 반납도 진행 중인 티켓을
     * 전제하므로 다시 나올 번호가 없다. 최종이라 410 으로 끊어 재시도를 막는다.
     */
    record SoldOutFinal() implements SeqOutcome {
    }

    /**
     * 이벤트 준비 전이다. 재고는 있을 수 있으므로 503 과 Retry-After 로 답한다.
     *
     * <p><b>누군가 재건해야 하는 상태이고 이 응답이 그 신호다.</b> 받은 요청이 재건을 띄우고,
     * 재건이 카운터를 세우면 이 갈래가 사라진다. 고리가 스스로 닫힌다.
     */
    record NotPrepared() implements SeqOutcome {
    }

    /**
     * 재건이 도는 중이다. 재고는 있을 수 있으므로 {@link NotPrepared} 와 같이 503 으로 답한다.
     *
     * <p><b>이 갈래를 받은 요청은 재건을 띄우지 않는다.</b> 이미 돌고 있기 때문이다. 한때 둘을
     * 같은 {@code -2} 로 내려받아 재건 중에도 "재건해라" 신호가 쏟아졌고, 재건이 끝나는 순간
     * 다음 재건이 예약된 상태가 됐다. 2026-10-12 Q-1 에서 한 대가 네 번 연달아 주도했다
     * ({@code docs/coupon/rebuild-redesign-2026-10-12.md} 6장).
     */
    record Rebuilding() implements SeqOutcome {
    }
}
