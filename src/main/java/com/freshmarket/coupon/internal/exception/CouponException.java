package com.freshmarket.coupon.internal.exception;

import com.freshmarket.common.exception.BusinessException;
import com.freshmarket.common.exception.ErrorCode;

// coupon 도메인에서 발생하는 실패를 나타내는 예외. 종류는 늘리지 않고 ErrorCode 로 구분한다
public class CouponException extends BusinessException {

    public CouponException(ErrorCode errorCode) {
        super(errorCode);
    }

    public CouponException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
