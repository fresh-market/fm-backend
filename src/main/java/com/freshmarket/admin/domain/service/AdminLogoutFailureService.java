package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminLogoutFailure;
import com.freshmarket.admin.domain.repository.AdminLogoutFailureRepository;
import com.freshmarket.admin.domain.repository.AdminRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자 로그아웃의 Refresh Token 정리(Redis/DB) 아웃박스. AdminAuthService.logout()의 즉시
 * 재시도(각 3회)가 다 실패하면 recordFailure()로 여기 남고, AdminLogoutFailureScheduler가
 * 매일 00:00에 retryAllPending()을 불러 재시도한다.
 *
 * retryAllPending() 전체를 @Transactional로 묶지 않는다 — 그 안에서 Redis 호출(네트워크 대기)이
 * 일어나는데, 트랜잭션 안에서 동기 외부 호출을 하면 그 대기 동안 DB 커넥션이 묶인다(DI-4-02와
 * 같은 이유). 그래서 재시도 결과 반영만 별도 빈(AdminLogoutFailureOutcomeService)의 짧은
 * 트랜잭션으로 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLogoutFailureService {

    private final AdminLogoutFailureRepository failureRepository;
    private final AdminRepository adminRepository;
    private final AdminRefreshTokenCleanupService cleanupService;
    private final AdminLogoutFailureOutcomeService outcomeService;

    /*
     * 같은 관리자에 대해 미해결 행이 있으면 재오픈하고, 없으면 새로 만든다. admin_id에 UNIQUE
     * 제약이 있어(이미 해결된 행 포함) 같은 관리자에게 새 행을 또 만들 수 없으므로, 이미 해결된
     * 행이 있어도 findByAdminId로 찾아 재오픈한다.
     */
    @Transactional
    void recordFailure(Long adminId, String refreshTokenHash, boolean redisFailed, boolean dbFailed) {
        failureRepository.findByAdminId(adminId).ifPresentOrElse(
                existing -> existing.reopen(refreshTokenHash, redisFailed, dbFailed),
                () -> failureRepository.save(
                        AdminLogoutFailure.record(adminId, refreshTokenHash, redisFailed, dbFailed)));
    }

    /** 미해결 행만 DB에서 조회해 재시도한다. */
    public void retryAllPending() {
        for (AdminLogoutFailure failure : failureRepository.findByResolvedFalse()) {
            retryOne(failure.getId());
        }
    }

    private void retryOne(Long failureId) {
        Optional<AdminLogoutFailure> maybeFailure = failureRepository.findById(failureId);
        if (maybeFailure.isEmpty()) {
            return;
        }
        AdminLogoutFailure failure = maybeFailure.get();
        Long adminId = failure.getAdminId();

        Optional<Admin> maybeAdmin = adminRepository.findById(adminId);
        if (maybeAdmin.isEmpty()) {
            // 관리자 계정 자체가 더는 없다 — 재시도할 대상이 없으니 다음 회차로 미룬다.
            log.warn("event=ADMIN_LOGOUT_OUTBOX_ADMIN_NOT_FOUND adminId={}", adminId);
            return;
        }
        String role = maybeAdmin.get().getRole().toAuthority();

        String tokenHash = failure.getRefreshTokenHash();
        boolean dbOk = !failure.isDbFailed();

        if (failure.isDbFailed()) {
            var dbState = cleanupService.revokeDbWithRetry(adminId);
            if (dbState != null) {
                dbOk = true;
                tokenHash = dbState.refreshTokenHash();
            }
        }

        boolean redisOk = !failure.isRedisFailed();
        if (failure.isRedisFailed() || (dbOk && failure.isDbFailed())) {
            // Redis가 원래도 실패했거나, DB 폐기가 이번에 새로 성공해 새 해시를 얻었다면
            // 그 해시로 Redis 기본 레코드까지 마저 정리한다.
            redisOk = cleanupService.cleanupRedisWithRetry(role, adminId, tokenHash);
        }

        outcomeService.applyOutcome(failureId, dbOk, redisOk, tokenHash);
    }
}