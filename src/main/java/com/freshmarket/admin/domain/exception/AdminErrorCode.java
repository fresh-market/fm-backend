package com.freshmarket.admin.domain.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AdminErrorCode implements ErrorCode {

    /*
     * 아이디 미존재와 비밀번호 불일치를 같은 코드로 묶는다.
     * 구분해서 응답하면 아이디 존재 여부가 그 자체로 정보가 된다 (요구사항: 실패 사유 미노출).
     */
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "ADMIN-001", "아이디 또는 비밀번호가 올바르지 않습니다."),

    // 관리자 삭제(비활성화) 처리된 계정. 잠금(5회 실패)은 이번 범위에서 제외했다
    ACCOUNT_INACTIVE(HttpStatus.FORBIDDEN, "ADMIN-002", "비활성화된 계정입니다."),

    SUPER_ADMIN_REQUIRED(HttpStatus.FORBIDDEN, "ADMIN-005", "최고관리자 권한이 필요합니다."),

    LOGIN_ID_DUPLICATED(HttpStatus.CONFLICT, "ADMIN-006", "이미 사용 중인 관리자 아이디입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}