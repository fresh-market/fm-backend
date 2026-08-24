package com.freshmarket.admin.domain.exception;

import com.freshmarket.common.exception.BusinessException;
import com.freshmarket.common.exception.ErrorCode;

/*
 * 관리자 인증의 일시적인 인프라 실패를 서비스 경계의 비즈니스 예외로 변환한다.
 * AdminException의 noRollbackFor 정책과 분리해, 재발급 DB 폴백 중 이 예외가 발생하면 DB 변경은 롤백되게 한다.
 */
public final class AdminAuthUnavailableException extends BusinessException {

    public AdminAuthUnavailableException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}