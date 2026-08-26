package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.entity.AdminAuditFailure;
import com.freshmarket.admin.domain.repository.AdminAuditFailureRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAuditFailureService {

    private final AdminAuditFailureRepository failureRepository;
    private final AdminAuditFailureRetryTransactionService retryTransactionService;

    /** 원래 감사 로그 저장이 실패하면 독립 트랜잭션으로 재시도 작업을 내구성 있게 남긴다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    void recordFailure(Long adminId, String action, String target, String detail) {
        failureRepository.saveAndFlush(AdminAuditFailure.record(adminId, action, target, detail));
    }

    public void retryAllPending() {
        long lastSeenId = 0L;
        while (true) {
            List<AdminAuditFailure> failures =
                    failureRepository.findTop100ByResolvedFalseAndIdGreaterThanOrderByIdAsc(lastSeenId);
            if (failures.isEmpty()) {
                return;
            }
            for (AdminAuditFailure failure : failures) {
                lastSeenId = failure.getId();
                retryTransactionService.retryOne(failure.getId());
            }
            if (failures.size() < 100) {
                return;
            }
        }
    }
}