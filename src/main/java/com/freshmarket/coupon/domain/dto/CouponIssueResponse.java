package com.freshmarket.coupon.domain.dto;

/**
 * 선착순 발급의 성공 응답이다. 본문에 순번만 담는다({@code docs/coupon/coupon.md} 3장).
 *
 * <p>{@code alreadyIssued} 가 붙은 이유는 서버가 "사용자가 다시 눌렀다" 와 "클라이언트가 자동으로
 * 재시도했다" 를 구분하지 못하기 때문이다. 둘을 같은 200 으로 답하되 이 플래그로 화면을 가른다.
 * 실패로 답하면 첫 응답을 잃은 사용자가 못 받은 줄 알고 계속 다시 눌러 그것이 다시 부하가 된다.
 */
public record CouponIssueResponse(int issueSeq, boolean alreadyIssued) {

    public static CouponIssueResponse issued(int issueSeq) {
        return new CouponIssueResponse(issueSeq, false);
    }

    public static CouponIssueResponse alreadyIssued(int issueSeq) {
        return new CouponIssueResponse(issueSeq, true);
    }
}
