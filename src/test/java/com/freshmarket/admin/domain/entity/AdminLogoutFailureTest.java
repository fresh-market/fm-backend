package com.freshmarket.admin.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

class AdminLogoutFailureTest {

    @Test
    void record는_시도횟수_1로_시작하고_미해결_상태다() {
        AdminLogoutFailure failure = AdminLogoutFailure.record(1L, "hash", true, false);

        assertThat(failure.getAdminId()).isEqualTo(1L);
        assertThat(failure.getRefreshTokenHash()).isEqualTo("hash");
        assertThat(failure.isRedisFailed()).isTrue();
        assertThat(failure.isDbFailed()).isFalse();
        assertThat(failure.isResolved()).isFalse();
        assertThat(failure.isProcessing()).isFalse();
        assertThat(failure.getProcessingStartedAt()).isNull();
    }

    @Test
    void record는_Redis와_DB가_둘_다_성공_상태면_거부한다() {
        assertThatThrownBy(() -> AdminLogoutFailure.record(1L, "hash", false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reopen도_Redis와_DB가_둘_다_성공_상태면_거부한다() {
        AdminLogoutFailure failure = AdminLogoutFailure.record(1L, null, false, true);

        assertThatThrownBy(() -> failure.reopen("hash", false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void record는_adminId가_null이면_거부한다() {
        assertThatThrownBy(() -> AdminLogoutFailure.record(null, "hash", true, false))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("adminId");
    }

    @Test
    void reopen하면_시도횟수가_1로_초기화되고_미해결_상태로_돌아간다() {
        AdminLogoutFailure failure = AdminLogoutFailure.record(1L, null, false, true);
        failure.applyRetryOutcome(true, true, "hash1"); // resolved=true 로 만든다
        ReflectionTestUtils.setField(failure, "processing", true);
        ReflectionTestUtils.setField(failure, "processingStartedAt", LocalDateTime.now());

        failure.reopen("hash2", true, false);

        assertThat(failure.getRefreshTokenHash()).isEqualTo("hash2");
        assertThat(failure.isRedisFailed()).isTrue();
        assertThat(failure.isDbFailed()).isFalse();
        assertThat(failure.isResolved()).isFalse();
        assertThat(failure.isProcessing()).isFalse();
        assertThat(failure.getProcessingStartedAt()).isNull();
    }

    @Test
    void 재시도_결과가_둘_다_성공이면_resolved가_되고_선점을_반납한다() {
        AdminLogoutFailure failure = AdminLogoutFailure.record(1L, null, true, true);
        ReflectionTestUtils.setField(failure, "processing", true);
        ReflectionTestUtils.setField(failure, "processingStartedAt", LocalDateTime.now());

        failure.applyRetryOutcome(true, true, "hash");

        assertThat(failure.isResolved()).isTrue();
        assertThat(failure.isDbFailed()).isFalse();
        assertThat(failure.isRedisFailed()).isFalse();
        assertThat(failure.getRefreshTokenHash()).isEqualTo("hash");
        assertThat(failure.isProcessing()).isFalse();
        assertThat(failure.getProcessingStartedAt()).isNull();
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

    @Test
    void releaseProcessing은_선점_상태와_시각을_함께_비운다() {
        AdminLogoutFailure failure = AdminLogoutFailure.record(1L, "hash", true, false);
        ReflectionTestUtils.setField(failure, "processing", true);
        ReflectionTestUtils.setField(failure, "processingStartedAt", LocalDateTime.now());

        failure.releaseProcessing();

        assertThat(failure.isProcessing()).isFalse();
        assertThat(failure.getProcessingStartedAt()).isNull();
    }
}