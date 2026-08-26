package com.freshmarket.admin.domain;

import com.freshmarket.admin.domain.service.AdminAuditFailureService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 감사 로그 저장 실패 아웃박스를 10분 간격으로 재시도한다. */
@Component
@Profile("batch")
@RequiredArgsConstructor
class AdminAuditFailureScheduler {

    private static final long RETRY_DELAY_MS = 10 * 60 * 1000L;

    private final AdminAuditFailureService adminAuditFailureService;

    @Scheduled(fixedDelay = RETRY_DELAY_MS)
    void retryPendingAuditFailures() {
        adminAuditFailureService.retryAllPending();
    }
}