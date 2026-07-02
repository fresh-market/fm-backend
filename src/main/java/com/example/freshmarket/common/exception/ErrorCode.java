package com.example.freshmarket.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 우리가 발생시킬 에러 종류들
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다."), INVALID_PASSWORD(
            HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다."), INTERNAL_SERVER_ERROR(
                    HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부에 에러가 발생했습니다."), NOT_FOUND(
                            HttpStatus.NOT_FOUND, "요청하신 페이지나 API를 찾을 수 없습니다."),


    // ADMIN
    ADMIN_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 관리자입니다."),
    NOT_SUPER_ADMIN(HttpStatus.BAD_REQUEST, "최고관리자가 아닙니다."),




    ;

    private final HttpStatus httpStatus; // 400, 404, 500 등
    private final String message;
}
