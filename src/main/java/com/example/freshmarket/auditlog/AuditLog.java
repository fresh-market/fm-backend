package com.example.freshmarket.auditlog;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long id;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(length = 100)
    private String target;

    @Lob
    private String detail;

    @CreatedDate
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // AuditLog는 Service의 개념이 아니라 정적 팩토리 메서드로 만들었음
    private AuditLog(Long adminId, String action, String target, String detail) {
        this.adminId = adminId;
        this.action = action;
        this.target = target;
        this.detail = detail;
    }

    public static AuditLog create (Long adminId, String action, String target, String detail) {
        return new AuditLog(adminId, action, target, detail);
    }
}