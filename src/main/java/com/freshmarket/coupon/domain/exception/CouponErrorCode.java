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
    /*
     * 줄 번호가 없지만 미확정 순번을 쥔 사람이 있다. 기준 시간이 지나면 그 번호가 다시 나온다.
     * 그래서 재시도할 값이 있고, 409 가 "지금 상태와 충돌한다" 라는 뜻을 그대로 준다.
     */
    SOLD_OUT(HttpStatus.CONFLICT, "COUPON-005", "쿠폰이 모두 소진되었습니다. 잠시 후 다시 시도해주세요."),
    /*
     * 줄 번호가 없고 쥔 사람도 없다. 다시 나올 번호가 없으므로 재시도할 값이 없다.
     * 410 으로 끊는 것은 동접 2만에 재고 1만이면 소진 응답이 만 건이라, 그들이 전부 재시도하면
     * 가장 힘든 순간에 부하가 두 배가 되기 때문이다 (docs/coupon/coupon.md 3장).
     */
    SOLD_OUT_FINAL(HttpStatus.GONE, "COUPON-012", "쿠폰 발급이 마감되었습니다."),
    // 재고는 있는데 지금 처리하지 못했다. 다시 시도할 값이 있다
    CONGESTED(HttpStatus.SERVICE_UNAVAILABLE, "COUPON-006", "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해주세요."),
    /*
     * 관리자가 이미 시작한 이벤트의 발급 시각을 바꾸려 했다.
     * 시작 전에는 얼마든지 바꿀 수 있다.
     */
    ISSUE_PERIOD_LOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "COUPON-007", "이미 시작한 이벤트의 발급 시각은 바꿀 수 없습니다."),
    /*
     * 관리자가 마감 대기가 끝나기 전에 이벤트를 끄려 했다.
     * 약속한 이벤트를 관리자가 도중에 흔들지 못하게 막고, 진행 중인 발급이 결판나기를 기다린다.
     */
    EVENT_NOT_CLOSABLE(HttpStatus.UNPROCESSABLE_CONTENT, "COUPON-008", "마감 시각에서 60초가 지나야 종료할 수 있습니다."),
    // 없는 발급분이거나 남의 것이다. 둘을 가르지 않아야 남의 쿠폰의 존재를 알아낼 수 없다
    MEMBER_COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "COUPON-009", "보유하지 않은 쿠폰입니다."),
    /*
     * 지금 상태에서 그 전이가 허용되지 않는다.
     * 이미 그 상태인 경우는 여기 오지 않는다. 그것은 늦게 도착한 같은 요청이라 성공으로 답한다.
     */
    INVALID_STATUS_TRANSITION(HttpStatus.UNPROCESSABLE_CONTENT, "COUPON-010", "지금 상태에서는 할 수 없는 처리입니다."),
    /*
     * 상태는 쓸 수 있는데 쿠폰의 사용 유효기간 밖이다.
     * 만료 배치가 아직 표시를 못 옮겼어도 이 쿠폰은 쓸 수 없다.
     */
    NOT_USABLE_PERIOD(HttpStatus.UNPROCESSABLE_CONTENT, "COUPON-011", "지금은 사용할 수 있는 기간이 아닙니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
