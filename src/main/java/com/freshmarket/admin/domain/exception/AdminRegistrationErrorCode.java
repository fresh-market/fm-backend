package com.freshmarket.admin.domain.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * 로그인/로그아웃/토큰 재발급 브랜치가 AdminErrorCode를 함께 수정하고 있으므로 계정 발급 오류를
 * 별도 enum으로 분리한다. 외부 오류 코드는 docs/api/admin.md 계약(ADMIN-005, ADMIN-006)을 그대로 유지한다.
 */
@Getter
@RequiredArgsConstructor
public enum AdminRegistrationErrorCode implements ErrorCode {

    SUPER_ADMIN_REQUIRED(HttpStatus.FORBIDDEN, "ADMIN-005", "최고관리자 권한이 필요합니다."),
    LOGIN_ID_DUPLICATED(HttpStatus.CONFLICT, "ADMIN-006", "이미 사용 중인 관리자 아이디입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}