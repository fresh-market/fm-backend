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

    // ADMIN-003~008은 auth/admin 문서의 비밀번호 변경·계정 관리 오류에 예약되어 있다.
    ADMIN_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN-009", "관리자 계정을 찾을 수 없습니다."),

    // Access Token 차단 상태를 확정하지 못한 경우 성공으로 응답하지 않는다.
    LOGOUT_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "ADMIN-010", "로그아웃 처리 상태를 확인할 수 없습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}