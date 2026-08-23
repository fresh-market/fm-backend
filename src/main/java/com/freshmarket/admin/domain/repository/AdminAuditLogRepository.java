package com.freshmarket.admin.domain.repository;

import com.freshmarket.admin.domain.entity.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {
}
