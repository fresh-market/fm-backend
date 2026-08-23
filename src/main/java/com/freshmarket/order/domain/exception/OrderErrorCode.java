package com.freshmarket.order.domain.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER-001", "주문을 찾을 수 없습니다."),
    INVALID_ORDER_PERIOD(HttpStatus.BAD_REQUEST, "ORDER-002", "조회 시작일은 종료일보다 늦을 수 없습니다."),
    // MemberApi.findAddress가 소유권까지 함께 검증하므로, 존재하지 않는 addressId와 남의
    // addressId를 이 코드 하나로 구분 없이 돌려준다(주소 존재 여부를 노출하지 않는다).
    ADDRESS_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, "ORDER-003", "배송지를 찾을 수 없습니다."),
    // 같은 requestId가 이전과 다른 내용(장바구니 항목/배송지/메시지)으로 다시 왔다 — 재시도가
    // 아니라 요청 식별자를 잘못 재사용한 경우다.
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "ORDER-004", "동일한 요청 식별자가 다른 내용으로 이미 사용됐습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
