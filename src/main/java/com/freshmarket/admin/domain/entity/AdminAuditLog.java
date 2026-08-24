package com.freshmarket.admin.domain.entity;

import com.freshmarket.common.entity.BaseImmutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "audit_log")
@AttributeOverride(name = "id", column = @Column(name = "audit_log_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAuditLog extends BaseImmutableTimeEntity {

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(length = 100)
    private String target;

    @Column(columnDefinition = "TEXT")
    private String detail;

    private AdminAuditLog(Long adminId, String action, String target, String detail) {
        this.adminId = adminId;
        this.action = action;
        this.target = target;
        this.detail = detail;
    }

    public static AdminAuditLog of(Long adminId, String action, String target, String detail) {
        if (adminId == null) {
            throw new IllegalArgumentException("adminId 는 필수다");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action 은 필수다");
        }
        return new AdminAuditLog(adminId, action, target, detail);
    }
}