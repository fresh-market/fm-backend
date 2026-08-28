package com.freshmarket.coupon.domain.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * coupon 도메인이 쓰는 오류 코드 모음이다.
 *
 * 소진과 혼잡을 다른 코드로 둔 것이 이 목록의 요점이다. 둘을 뭉치면 재고가 남았는데 마감으로
 * 답하거나 끝난 이벤트에 재시도를 유도한다 (docs/coupon/coupon.md 3장).
 */
@Getter
@RequiredArgsConstructor
public enum CouponErrorCode implements ErrorCode {

    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "COUPON-001", "없는 쿠폰입니다."),
    NOT_ISSUABLE(HttpStatus.UNPROCESSABLE_CONTENT, "COUPON-002", "지금은 발급할 수 없는 쿠폰입니다."),
    NOT_TARGET_GRADE(HttpStatus.UNPROCESSABLE_CONTENT, "COUPON-003", "발급 대상 등급이 아닙니다."),
    NOT_LIMITED(HttpStatus.UNPROCESSABLE_CONTENT, "COUPON-004", "선착순 발급 대상 쿠폰이 아닙니다."),
    // 재고가 없다. 최종이라 다시 시도해도 달라지지 않는다
    SOLD_OUT(HttpStatus.CONFLICT, "COUPON-005", "쿠폰이 모두 소진되었습니다."),
    // 재고는 있는데 지금 처리하지 못했다. 다시 시도할 값이 있다
    CONGESTED(HttpStatus.SERVICE_UNAVAILABLE, "COUPON-006", "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해주세요."),
    /*
     * 관리자가 이미 시작한 이벤트의 발급 시각을 바꾸려 했다.
     * 시작 전에는 얼마든지 바꿀 수 있다.
     */
    ISSUE_PERIOD_LOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "COUPON-007", "이미 시작한 이벤트의 발급 시각은 바꿀 수 없습니다."),
    /*
     * 관리자가 소진 전이고 마감 전인 이벤트를 끄려 했다.
     * 약속한 이벤트를 관리자가 도중에 흔들지 못하게 막는다.
     */
    EVENT_NOT_CLOSABLE(HttpStatus.UNPROCESSABLE_CONTENT, "COUPON-008", "소진되지 않았고 마감 시각도 지나지 않아 종료할 수 없습니다."),
    // 없는 발급분이거나 남의 것이다. 둘을 가르지 않아야 남의 쿠폰의 존재를 알아낼 수 없다
    MEMBER_COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "COUPON-009", "보유하지 않은 쿠폰입니다."),
    /*
     * 지금 상태에서 그 전이가 허용되지 않는다.
     * 이미 그 상태인 경우는 여기 오지 않는다. 그것은 늦게 도착한 같은 요청이라 성공으로 답한다.
     */
    INVALID_STATUS_TRANSITION(HttpStatus.UNPROCESSABLE_CONTENT, "COUPON-010", "지금 상태에서는 할 수 없는 처리입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
