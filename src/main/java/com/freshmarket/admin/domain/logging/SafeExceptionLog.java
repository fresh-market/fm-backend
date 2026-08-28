package com.freshmarket.admin.domain.logging;

/**
 * 저장소 예외를 로그에 남길 때 민감한 message/cause는 제거하고
 * 예외 타입과 발생 위치의 stack trace만 보존하는 공통 변환기.
 */
public final class SafeExceptionLog {

    private SafeExceptionLog() {
    }

    public static String errorType(Throwable error) {
        return error == null ? "UNCONFIRMED" : error.getClass().getSimpleName();
    }

    public static Throwable stackTrace(Throwable error) {
        if (error == null) {
            return null;
        }

        RuntimeException sanitized = new RuntimeException(error.getClass().getSimpleName());
        sanitized.setStackTrace(error.getStackTrace());
        return sanitized;
    }
}