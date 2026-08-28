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

    /**
     * 이번에는 못 썼고 다시 시도할 값이 있다. 순번 충돌과 일시적인 DB 실패가 여기로 온다.
     *
     * <p>사유를 들고 다니는 이유가 있다. 서비스가 이것을 503 으로 옮기고 나면 <b>순번 충돌인지
     * DB 실패인지가 사라진다.</b> 8장은 그 둘을 나눠 세라고 요구한다.
     */
    record Congested(IssueResult reason) implements IssueOutcome {
    }

    /**
     * 다시 시도해도 같을 실패다. SQL 문법 오류처럼 고쳐야 할 것이 여기로 온다.
     *
     * <p>{@link Congested} 와 나눈 이유가 있다. 버그까지 "잠시 후 다시" 로 덮으면 그것이
     * <b>재시도에 묻혀 배포 뒤에도 한참 안 드러난다.</b> 서버 오류로 답해 눈에 띄게 둔다.
     */
    record Failed() implements IssueOutcome {
    }
}
