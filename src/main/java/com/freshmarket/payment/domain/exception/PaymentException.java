package com.freshmarket.payment.domain.exception;

import com.freshmarket.common.exception.BusinessException;
import com.freshmarket.common.exception.ErrorCode;

public class PaymentException extends BusinessException {

    public PaymentException(ErrorCode errorCode) {
        super(errorCode);
    }

    public PaymentException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
