package com.freshmarket.payment.domain.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
    PAYMENT_ALREADY_COMPLETED(HttpStatus.CONFLICT, "PAYMENT-001", "이미 결제된 주문입니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT-002", "결제 정보를 찾을 수 없습니다."),
    PAYMENT_REQUEST_MISMATCH(HttpStatus.CONFLICT, "PAYMENT-003", "기존 결제 요청과 금액 또는 수단이 다릅니다."),
    PAYMENT_NOT_PENDING(HttpStatus.CONFLICT, "PAYMENT-004", "승인 대기 상태의 결제가 아닙니다."),
    INVALID_PAYMENT_REQUEST(HttpStatus.BAD_REQUEST, "PAYMENT-005", "결제 요청 값이 올바르지 않습니다."),
    INVALID_PAYMENT_APPROVAL(HttpStatus.BAD_REQUEST, "PAYMENT-006", "결제 승인 결과 값이 올바르지 않습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
