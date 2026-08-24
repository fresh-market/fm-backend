package com.freshmarket.member.domain.exception;

import com.freshmarket.common.exception.BusinessException;

public class MemberException extends BusinessException {

    public MemberException(MemberErrorCode errorCode) {
        super(errorCode);
    }

    public MemberException(MemberErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
