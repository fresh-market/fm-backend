package com.freshmarket.admin.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 감사 로그 저장 실패를 유실하지 않고 DB에 남겨 배치가 재시도하기 위한 내구성 아웃박스. */
@Entity
@Getter
@Table(name = "admin_audit_failure")
@AttributeOverride(name = "id", column = @Column(name = "admin_audit_failure_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAuditFailure extends BaseMutableTimeEntity {

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(length = 100)
    private String target;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private boolean resolved;

    private AdminAuditFailure(Long adminId, String action, String target, String detail) {
        this.adminId = Objects.requireNonNull(adminId, "adminId");
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action 은 필수다");
        }
        this.action = action;
        this.target = target;
        this.detail = detail;
        this.attemptCount = 1;
    }

    public static AdminAuditFailure record(Long adminId, String action, String target, String detail) {
        return new AdminAuditFailure(adminId, action, target, detail);
    }

    public void markResolved() {
        this.attemptCount++;
        this.resolved = true;
    }
}