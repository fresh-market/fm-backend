package com.freshmarket.coupon.domain.issue;

/**
 * 발급 한 건이 어떻게 끝났는지를 나타낸다. 앱이 지표의 {@code result} 태그로 쓰는 값이다.
 *
 * <p>{@code docs/coupon/coupon.md} 8장이 <b>"충돌, 소진, 혼잡, DB 타임아웃을 나눠 센다"</b> 고
 * 요구한다. 상태 코드만으로는 소진(409)과 혼잡(503)까지만 갈리고, 혼잡 안에서 순번 충돌과
 * 큐 포화와 DB 실패가 한 덩어리가 된다. 그것을 가르려고 둔다.
 *
 * <p>재요청 비율도 이 값에서 나온다. 대시보드가 {@link #ALREADY_ISSUED} 를 전체로 나누면 된다.
 */
public enum IssueResult {

    /** 이번에 발급됐다. */
    ISSUED("issued"),
    /** 이미 갖고 있던 쿠폰이다. 재요청 비율이 이 값으로 나온다. */
    ALREADY_ISSUED("already-issued"),
    /** 재고가 없다. 최종이라 다시 시도해도 같다. */
    SOLD_OUT("sold-out"),
    /** 기간이 아니거나 스위치가 꺼졌거나 대상 등급이 아니다. */
    NOT_ISSUABLE("not-issuable"),

    /** 쿠폰이나 회원을 읽지 못했다. DB 가 일시적으로 답하지 않는다. */
    READ_FAILED("congested-read"),
    /** DB 쓰기가 계속 실패해 쓰기 회로가 열렸다. */
    WRITE_CIRCUIT("congested-write-circuit"),
    /** 큐가 상한에 닿았다. 순번을 받기 전에 끊었다. */
    QUEUE_FULL("congested-queue-full"),
    /** Redis 가 답하지 않거나 순번 회로가 열렸다. */
    SEQ_UNAVAILABLE("congested-seq-unavailable"),
    /** 카운터가 없다. 이벤트 준비 전이거나 재건 중이다. */
    NOT_PREPARED("congested-not-prepared"),
    /** 그 번호를 남이 쓰고 있다. 8장이 말하는 "충돌" 이다. */
    SEQ_TAKEN("congested-seq-taken"),
    /** DB 쓰기가 실패했다. 8장이 말하는 "DB 타임아웃" 이 여기 든다. */
    DB_FAILED("congested-db-failed"),
    /** 요청 예산 안에 못 끝냈다. 그 항목은 큐에 남아 결국 써진다. */
    BUDGET_EXCEEDED("congested-budget"),
    /** 배치가 통째로 어긋났거나 앱이 내려가는 중이다. */
    ABORTED("congested-aborted");

    private final String tag;

    IssueResult(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
