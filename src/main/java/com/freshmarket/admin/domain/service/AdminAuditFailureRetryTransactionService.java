package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.entity.AdminAuditLog;
import com.freshmarket.admin.domain.repository.AdminAuditFailureRepository;
import com.freshmarket.admin.domain.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class AdminAuditFailureRetryTransactionService {

    private final AdminAuditFailureRepository failureRepository;
    private final AdminAuditLogRepository auditLogRepository;

    /** 행 잠금 안에서 감사 로그 INSERT와 outbox 해결 표시를 한 트랜잭션으로 묶어 중복 기록을 막는다. */
    @Transactional(timeout = 5)
    void retryOne(Long failureId) {
        failureRepository.findByIdForUpdate(failureId).ifPresent(failure -> {
            if (failure.isResolved()) {
                return;
            }
            auditLogRepository.save(AdminAuditLog.of(
                    failure.getAdminId(), failure.getAction(), failure.getTarget(), failure.getDetail()));
            failure.markResolved();
        });
    }
}