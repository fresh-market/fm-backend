package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminLogoutFailure;
import com.freshmarket.admin.domain.repository.AdminLogoutFailureRepository;
import com.freshmarket.admin.domain.repository.AdminRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자 로그아웃의 Refresh Token 정리(Redis/DB) 아웃박스. AdminAuthService.logout()의 즉시
 * 재시도(각 3회)가 다 실패하면 recordFailure()로 여기 남고, AdminLogoutFailureScheduler가
 * 10분 간격으로 retryAllPending()을 불러 재시도한다.
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

    private static final Duration CLAIM_LEASE = Duration.ofMinutes(10);

    private final AdminLogoutFailureRepository failureRepository;
    private final AdminRepository adminRepository;
    private final AdminRefreshTokenCleanupService cleanupService;
    private final AdminLogoutFailureOutcomeService outcomeService;
    private final Clock clock;

    /*
     * 같은 관리자·같은 Refresh Token의 실패 기록이 동시에 생성되는 경우를 DB의 원자적 upsert로 처리한다.
     * (admin_id, refresh_token_hash) 복합 UNIQUE 덕분에 동일 RT 실패는 한 행으로 합쳐지고, 다른 RT 실패는
     * 별도 행으로 남는다. 따라서 재로그인 뒤 새 RT 폐기까지 실패해도 이전 RT의 미해결 작업을 덮어쓰지 않는다.
     *
     * 실패 기록은 바깥 작업의 성공/롤백과 분리되어야 하므로 REQUIRES_NEW로 독립 커밋한다.
     * 현재 logout() 자체는 트랜잭션이 아니지만, 향후 트랜잭션 안에서 호출되더라도 실패 이력이
     * 상위 트랜잭션 롤백에 같이 사라지지 않도록 경계를 명시한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordFailure(Long adminId, String refreshTokenHash, boolean redisFailed, boolean dbFailed) {
        if (!redisFailed && !dbFailed) {
            throw new IllegalArgumentException("redisFailed 또는 dbFailed 중 하나는 true여야 한다");
        }

        failureRepository.upsertFailure(
                adminId,
                refreshTokenHash,
                redisFailed,
                dbFailed,
                LocalDateTime.now(clock));
    }

    /**
     * 미해결 행을 PK 커서 기준 100건씩 읽어 재시도한다. 한 청크 처리 중 resolved 값이 바뀌어도
     * 마지막으로 본 PK 다음부터 이어가므로 offset pagination처럼 행을 건너뛰지 않는다.
     */
    public void retryAllPending() {
        long lastSeenId = 0L;

        while (true) {
            List<AdminLogoutFailure> failures =
                    failureRepository.findTop100ByResolvedFalseAndIdGreaterThanOrderByIdAsc(lastSeenId);
            if (failures.isEmpty()) {
                return;
            }

            Map<Long, Admin> adminsById = loadAdmins(failures);

            for (AdminLogoutFailure failure : failures) {
                lastSeenId = failure.getId();
                retryOne(failure.getId(), adminsById.get(failure.getAdminId()));
            }

            if (failures.size() < 100) {
                return;
            }
        }
    }

    private Map<Long, Admin> loadAdmins(List<AdminLogoutFailure> failures) {
        Set<Long> adminIds = failures.stream()
                .map(AdminLogoutFailure::getAdminId)
                .collect(Collectors.toSet());

        return adminRepository.findAllById(adminIds).stream()
                .collect(Collectors.toMap(Admin::getId, Function.identity()));
    }

    private void retryOne(Long failureId, Admin admin) {
        LocalDateTime claimedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime staleBefore = claimedAt.minus(CLAIM_LEASE);
        if (failureRepository.claimForRetry(failureId, claimedAt, staleBefore) != 1) {
            return;
        }

        Optional<AdminLogoutFailure> maybeFailure =
                failureRepository.findById(failureId);

        if (maybeFailure.isEmpty()) {
            outcomeService.releaseClaim(failureId, claimedAt);
            return;
        }

        AdminLogoutFailure failure = maybeFailure.get();
        Long adminId = failure.getAdminId();

        if (admin == null) {
            log.warn(
                    "event=ADMIN_LOGOUT_OUTBOX_ADMIN_NOT_FOUND adminId={}",
                    adminId);

            outcomeService.releaseClaim(failureId, claimedAt);
            return;
        }

        String role = admin.getRole().toAuthority();

        String tokenHash = failure.getRefreshTokenHash();
        boolean dbOk = !failure.isDbFailed();

        if (failure.isDbFailed()) {
            if (tokenHash == null) {
                // 실패 당시 해시가 없으면 현재 DB의 토큰이 과거 로그아웃 대상인지,
                // 그 뒤 재로그인으로 새로 발급된 토큰인지 구분할 수 없다.
                // adminId만 보고 무조건 지우면 새 세션을 끊을 수 있으므로 지연 재시도에서는 건드리지 않는다.
                log.warn("event=ADMIN_LOGOUT_DB_RETRY_SKIPPED_MISSING_HASH adminId={}", adminId);
                dbOk = false;
            } else {
                dbOk = cleanupService.revokeDbIfMatchesWithRetry(adminId, tokenHash);
            }
        }

        boolean redisOk = !failure.isRedisFailed();
        if (failure.isRedisFailed()) {
            redisOk = cleanupService.cleanupRedisWithRetry(role, adminId, tokenHash);
        }

        outcomeService.applyOutcome(failureId, claimedAt, dbOk, redisOk, tokenHash);
    }
}