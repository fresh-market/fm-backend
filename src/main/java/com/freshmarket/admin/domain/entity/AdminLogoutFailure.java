package com.freshmarket.admin.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 관리자 로그아웃의 Refresh Token 정리(Redis 삭제 + DB 폐기)가 즉시 재시도(3회)까지 실패하면
 * 이 행으로 남는다 — AdminLogoutFailureScheduler가 매일 00:00에 미해결 건을 재시도한다.
 *
 * kakao_unlink_failure(KakaoUnlinkFailure)와 같은 아웃박스 패턴이지만, 성공해도 행을 지우지
 * 않는다는 점이 다르다. 로그아웃 실패 중 DB에 Refresh Token이 남는 경우는 보안 이력으로 남겨두는
 * 게 낫다고 판단해, resolved 플래그로만 완료 여부를 구분한다(감사 목적).
 *
 * PK 컬럼명은 스키마 전체 컨벤션(schema-design-rationale.md)대로 admin_logout_failure_id다 —
 * BaseMutableTimeEntity의 id 필드는 컬럼명을 "id"로 매핑하므로, @AttributeOverride로 실제 DDL의
 * PK 컬럼명에 맞춰준다.
 */
@Entity
@Getter
@Table(name = "admin_logout_failure")
@AttributeOverride(name = "id", column = @Column(name = "admin_logout_failure_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminLogoutFailure extends BaseMutableTimeEntity {

    @Column(name = "admin_id", nullable = false, unique = true)
    private Long adminId;

    @Column(name = "refresh_token_hash", length = 64)
    private String refreshTokenHash;

    @Column(name = "redis_failed", nullable = false)
    private boolean redisFailed;

    @Column(name = "db_failed", nullable = false)
    private boolean dbFailed;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    /**
     * 배치 인스턴스가 둘 이상이어도 같은 실패 건의 외부 Redis 정리를 동시에 실행하지 않도록
     * 조건부 UPDATE로 선점할 때 사용하는 상태다. 일정 시간이 지난 선점은 재획득할 수 있어
     * 프로세스가 중간에 종료되어도 영구적으로 멈추지 않는다.
     */
    @Column(name = "processing", nullable = false)
    private boolean processing;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    private AdminLogoutFailure(Long adminId, String refreshTokenHash, boolean redisFailed, boolean dbFailed) {
        this.adminId = Objects.requireNonNull(adminId, "adminId");
        validateFailureState(redisFailed, dbFailed);
        this.refreshTokenHash = refreshTokenHash;
        this.redisFailed = redisFailed;
        this.dbFailed = dbFailed;
        this.attemptCount = 1;
    }

    /** 즉시 재시도(3회)가 다 실패한 뒤 처음 기록할 때 쓰는 유일한 생성 진입점. */
    public static AdminLogoutFailure record(
            Long adminId, String refreshTokenHash, boolean redisFailed, boolean dbFailed) {
        return new AdminLogoutFailure(adminId, refreshTokenHash, redisFailed, dbFailed);
    }

    /**
     * 이미 해결(resolved=true)된 행에 같은 관리자가 다시 실패하면, 새 행 대신 이 행을 재오픈한다.
     * admin_id에 UNIQUE 제약이 있어 새 행을 만들 수 없기도 하고, 같은 관리자의 실패 이력을
     * 한 행에서 계속 추적하는 게 조회하기도 더 쉽다.
     */
    public void reopen(String refreshTokenHash, boolean redisFailed, boolean dbFailed) {
        validateFailureState(redisFailed, dbFailed);
        this.refreshTokenHash = refreshTokenHash;
        this.redisFailed = redisFailed;
        this.dbFailed = dbFailed;
        this.attemptCount = 1;
        this.resolved = false;
        releaseProcessing();
    }

    /**
     * 스케줄러의 재시도 결과를 반영한다. latestRefreshTokenHash는 DB 폐기가 이번에 새로 성공해
     * 얻은 해시가 있을 때만 갱신한다(null이면 기존 값을 유지) — DB가 이미 성공한 뒤라 다시 조회해도
     * 해시를 못 구하는 경우와, 이번에도 DB가 실패해 해시를 모르는 경우를 구분하지 않기 위함이다.
     */
    public void applyRetryOutcome(boolean dbNowOk, boolean redisNowOk, String latestRefreshTokenHash) {
        this.attemptCount++;
        this.dbFailed = !dbNowOk;
        this.redisFailed = !redisNowOk;
        if (latestRefreshTokenHash != null) {
            this.refreshTokenHash = latestRefreshTokenHash;
        }
        if (dbNowOk && redisNowOk) {
            this.resolved = true;
        }
        releaseProcessing();
    }

    /** 외부 작업을 시작하지 못한 경우 현재 선점만 반납한다. */
    public void releaseProcessing() {
        this.processing = false;
        this.processingStartedAt = null;
    }

    private static void validateFailureState(boolean redisFailed, boolean dbFailed) {
        if (!redisFailed && !dbFailed) {
            throw new IllegalArgumentException("redisFailed 또는 dbFailed 중 하나는 true여야 한다");
        }
    }
}