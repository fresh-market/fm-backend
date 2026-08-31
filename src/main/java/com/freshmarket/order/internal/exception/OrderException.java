package com.freshmarket.order.internal.exception;

import com.freshmarket.common.exception.BusinessException;
import com.freshmarket.common.exception.ErrorCode;

public class OrderException extends BusinessException {

    public OrderException(ErrorCode errorCode) {
        super(errorCode);
    }

    public OrderException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
