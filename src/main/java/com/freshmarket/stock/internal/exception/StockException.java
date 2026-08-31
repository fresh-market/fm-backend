package com.freshmarket.stock.internal.exception;

import com.freshmarket.common.exception.BusinessException;
import com.freshmarket.common.exception.ErrorCode;

// stock 도메인에서 발생하는 실패를 나타내는 예외. 종류는 늘리지 않고 StockErrorCode로 구분한다
public class StockException extends BusinessException {

    public StockException(ErrorCode errorCode) {
        super(errorCode);
    }

    public StockException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
