package com.freshmarket.admin.domain.batch;

import com.freshmarket.admin.domain.service.AdminLogoutFailureService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * admin_logout_failure에 쌓인 미해결 건을 10분 간격으로 재시도한다.
 * 스케줄러 어댑터이므로 구현 컴포넌트를 도메인 루트에 두지 않고 domain.batch에 둔다.
 * 재실행 안전성은 실패 행의 claim/lease와 해시 조건부 삭제로 보장한다. 실행/소요시간 로그는 SchedulerLoggingAspect가
 * @Scheduled 메서드마다 자동으로 남긴다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
class AdminLogoutFailureScheduler {

    private static final long RETRY_DELAY_MS = 10 * 60 * 1000L;

    private final AdminLogoutFailureService adminLogoutFailureService;

    /** 여러 인스턴스에서 동시에 실행돼도 실패 행 단위 claimForRetry() 조건부 UPDATE로
     *  한 실행자만 선점한다. 결과 반영도 processing_started_at(lease 식별자)이 같은 실행자만
     *  허용해 만료된 옛 실행자가 새 실행자의 결과를 덮어쓰지 못한다.
     */
    @Scheduled(fixedDelay = RETRY_DELAY_MS)
    void retryPendingLogoutFailures() {
        adminLogoutFailureService.retryAllPending();
    }
}