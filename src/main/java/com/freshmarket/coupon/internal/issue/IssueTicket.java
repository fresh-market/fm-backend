package com.freshmarket.coupon.internal.issue;

import java.util.concurrent.CompletableFuture;

import com.freshmarket.coupon.internal.entity.CouponScope;

/**
 * 요청 스레드가 큐에 넣는 한 건이다. {@code docs/coupon/coupon.md} 7장이 "인스턴스 큐에는 셋을
 * 저장한다" 고 정한 그 셋에, 플러시 스레드가 행을 만들 때 필요한 값만 더했다.
 *
 * <p>{@code future} 가 플러시 스레드와 요청 스레드를 잇는 유일한 통로다. 요청 스레드는 이것만
 * 기다리고 실패를 직접 보지 못한다. 반납도 실패를 잡은 플러시 스레드가 맡는다.
 *
 * @param issueLimit     쿠폰의 총 수량. {@code chk_mc_issue_seq} 가 순번과 함께 보므로 행에 넣는다
 * @param issueSeq       순번 확보에서 받은 번호
 * @param queuedAtNanos  큐에 들어간 시각. 플러시가 집을 때 빼서 큐 대기를 잰다
 */
public record IssueTicket(long couponId,
                          long memberId,
                          CouponScope scope,
                          int issueLimit,
                          int issueSeq,
                          long queuedAtNanos,
                          CompletableFuture<IssueOutcome> future) {

    /*
     * 큐 대기는 commit-wait 예산에서 유일하게 상한이 없는 항목인데 재는 것이 없었다.
     *
     * 나머지 셋은 각자 타임아웃이 막아 최악이 정해지지만 이것은 앞선 배치가 다 빠질 때까지다.
     * 그래서 예산을 넘긴 요청이 어디서 시간을 썼는지 알 수 없었다 (2026-10-10).
     */
    public static IssueTicket of(long couponId, long memberId, CouponScope scope, int issueLimit, int issueSeq) {
        return new IssueTicket(couponId, memberId, scope, issueLimit, issueSeq,
                System.nanoTime(), new CompletableFuture<>());
    }

    /*
     * 플러시 스레드가 이미 끝난 future 에 다시 결과를 써도 아무 일이 안 일어난다.
     * 요청 스레드가 예산을 넘겨 떠났으면 그 자리는 이미 닫혀 있고, 그것은 실패가 아니다.
     */
    public void complete(IssueOutcome outcome) {
        future.complete(outcome);
    }
}
