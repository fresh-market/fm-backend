package com.freshmarket.coupon.domain.redis;

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

    /** 재고가 없고 회수할 묶인 순번도 없다. 최종이라 4xx 로 끊는다. */
    record SoldOut() implements SeqOutcome {
    }

    /** 이벤트 준비 전이거나 카운터 재건 중이다. 재고는 있을 수 있으므로 503 과 Retry-After 로 답한다. */
    record NotPrepared() implements SeqOutcome {
    }
}
