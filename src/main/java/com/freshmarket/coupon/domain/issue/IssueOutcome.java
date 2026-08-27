package com.freshmarket.coupon.domain.issue;

/**
 * 플러시 스레드가 요청 스레드에게 돌려주는 결과다.
 *
 * <p>소진은 여기 없다. 재고 판정은 순번 확보에서 이미 끝나 큐까지 오지 않는다. 여기 남는 것은
 * DB 에 쓰다 갈리는 세 갈래뿐이다.
 */
public sealed interface IssueOutcome {

    /** 행이 들어갔다. */
    record Issued(int seq) implements IssueOutcome {
    }

    /**
     * 이 회원은 이미 이 쿠폰을 갖고 있었다({@code uk_mc_coupon_member}).
     *
     * <p>순번은 이번에 받은 것이 아니라 원래 갖고 있던 것이다. 실패로 답하지 않는 이유는
     * {@code docs/coupon/coupon.md} 3장이 정한 멱등 규칙이다.
     */
    record AlreadyIssued(int seq) implements IssueOutcome {
    }

    /** 이번에는 못 썼고 다시 시도할 값이 있다. 순번 충돌과 타임아웃이 여기로 온다. */
    record Congested() implements IssueOutcome {
    }
}
