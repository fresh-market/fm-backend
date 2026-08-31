package com.freshmarket.admin.internal.exception;

import com.freshmarket.common.exception.BusinessException;
import com.freshmarket.common.exception.ErrorCode;

public class AdminException extends BusinessException {

    public AdminException(ErrorCode errorCode) {
        super(errorCode);
    }

    public AdminException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}