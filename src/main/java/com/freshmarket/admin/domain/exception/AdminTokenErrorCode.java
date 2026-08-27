package com.freshmarket.admin.domain.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AdminTokenErrorCode implements ErrorCode {

    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "ADMIN-011", "만료되었거나 이미 사용된 토큰입니다."),
    REFRESH_TOKEN_RESULT_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE, "ADMIN-012", "토큰 재발급 처리 결과를 확인할 수 없습니다. 다시 로그인해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}