package com.freshmarket.product.internal.exception;

import com.freshmarket.common.exception.BusinessException;
import com.freshmarket.common.exception.ErrorCode;

// product 도메인에서 발생하는 실패를 나타내는 예외. 종류는 늘리지 않고 ProductErrorCode로 구분한다
public class ProductException extends BusinessException {

    public ProductException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ProductException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
