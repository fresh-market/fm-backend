package com.freshmarket.admin.internal.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeExceptionLogTest {

    @Test
    void 예외_타입은_메시지_없이_클래스명만_반환한다() {
        IllegalStateException error = new IllegalStateException("sensitive-message");

        assertThat(SafeExceptionLog.errorType(error))
                .isEqualTo("IllegalStateException");
    }

    @Test
    void null_예외_타입은_UNCONFIRMED로_표시한다() {
        assertThat(SafeExceptionLog.errorType(null))
                .isEqualTo("UNCONFIRMED");
    }

    @Test
    void 로그용_예외는_원본_message와_cause를_제거하고_stack_trace만_보존한다() {
        IllegalArgumentException cause = new IllegalArgumentException("db-host-secret");
        IllegalStateException error = new IllegalStateException("sql-sensitive-message", cause);
        StackTraceElement[] stackTrace = {
                new StackTraceElement("ExampleService", "run", "ExampleService.java", 42)
        };
        error.setStackTrace(stackTrace);

        Throwable sanitized = SafeExceptionLog.stackTrace(error);

        assertThat(sanitized)
                .isNotSameAs(error)
                .hasMessage("IllegalStateException")
                .hasNoCause();
        assertThat(sanitized.getStackTrace()).containsExactly(stackTrace);
    }

    @Test
    void null_예외는_로그용_예외도_null이다() {
        assertThat(SafeExceptionLog.stackTrace(null)).isNull();
    }
}