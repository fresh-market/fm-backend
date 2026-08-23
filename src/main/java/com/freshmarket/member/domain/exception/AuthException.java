package com.freshmarket.member.domain.exception;

import com.freshmarket.common.exception.BusinessException;

// (2026-08-18 12:10) docs/api/auth.md 기준 로그인/재발급 프로토콜 실패용 예외.
public class AuthException extends BusinessException {

    public AuthException(AuthErrorCode errorCode) {
        super(errorCode);
    }

    public AuthException(AuthErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
