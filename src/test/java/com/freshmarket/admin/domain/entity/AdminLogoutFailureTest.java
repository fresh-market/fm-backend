package com.freshmarket.admin.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminLogoutFailureTest {

    @Test
    void record는_시도횟수_1로_시작하고_미해결_상태다() {
        AdminLogoutFailure failure = AdminLogoutFailure.record(1L, "hash", true, false);

        assertThat(failure.getAdminId()).isEqualTo(1L);
        assertThat(failure.getRefreshTokenHash()).isEqualTo("hash");
        assertThat(failure.isRedisFailed()).isTrue();
        assertThat(failure.isDbFailed()).isFalse();
        assertThat(failure.isResolved()).isFalse();
    }

    @Test
    void reopen하면_시도횟수가_1로_초기화되고_미해결_상태로_돌아간다() {
        AdminLogoutFailure failure = AdminLogoutFailure.record(1L, null, false, true);
        failure.applyRetryOutcome(true, true, "hash1"); // resolved=true 로 만든다

        failure.reopen("hash2", true, false);

        assertThat(failure.getRefreshTokenHash()).isEqualTo("hash2");
        assertThat(failure.isRedisFailed()).isTrue();
        assertThat(failure.isDbFailed()).isFalse();
        assertThat(failure.isResolved()).isFalse();
    }

    @Test
    void 재시도_결과가_둘_다_성공이면_resolved가_된다() {
        AdminLogoutFailure failure = AdminLogoutFailure.record(1L, null, true, true);

        failure.applyRetryOutcome(true, true, "hash");

        assertThat(failure.isResolved()).isTrue();
        assertThat(failure.isDbFailed()).isFalse();
        assertThat(failure.isRedisFailed()).isFalse();
        assertThat(failure.getRefreshTokenHash()).isEqualTo("hash");
    }

    @Test
    void 재시도_결과가_하나라도_실패면_resolved가_되지_않는다() {
        AdminLogoutFailure failure = AdminLogoutFailure.record(1L, null, true, true);

        failure.applyRetryOutcome(true, false, "hash");

        assertThat(failure.isResolved()).isFalse();
        assertThat(failure.isDbFailed()).isFalse();
        assertThat(failure.isRedisFailed()).isTrue();
    }

    @Test
    void 새_해시가_없으면_기존_해시를_유지한다() {
        AdminLogoutFailure failure = AdminLogoutFailure.record(1L, "original", true, false);

        failure.applyRetryOutcome(true, false, null);

        assertThat(failure.getRefreshTokenHash()).isEqualTo("original");
    }
}