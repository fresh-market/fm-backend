package com.freshmarket.common.exception;

import org.springframework.dao.DataIntegrityViolationException;

// DB 예외의 근본 원인 메시지에 제약 이름이 들어있는지로 어떤 제약을 위반했는지 구분한다
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    public static boolean isConstraintViolation(DataIntegrityViolationException e, String constraintName) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(constraintName);
    }
}
