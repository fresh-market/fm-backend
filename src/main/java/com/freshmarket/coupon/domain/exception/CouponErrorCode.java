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
    CONGESTED(HttpStatus.SERVICE_UNAVAILABLE, "COUPON-006", "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
