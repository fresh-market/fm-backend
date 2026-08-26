package com.freshmarket.admin.domain.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AdminTokenErrorCode implements ErrorCode {

    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "ADMIN-011", "만료되었거나 이미 사용된 토큰입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}