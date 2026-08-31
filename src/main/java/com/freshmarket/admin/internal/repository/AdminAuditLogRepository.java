package com.freshmarket.admin.internal.repository;

import com.freshmarket.admin.internal.entity.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {
}