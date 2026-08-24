package com.freshmarket.order.domain.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER-001", "주문을 찾을 수 없습니다."),
    INVALID_ORDER_PERIOD(HttpStatus.BAD_REQUEST, "ORDER-002", "조회 시작일은 종료일보다 늦을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
