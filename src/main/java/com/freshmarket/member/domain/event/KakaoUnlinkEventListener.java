package com.freshmarket.member.domain.event;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.domain.client.KakaoUnlinkClient;
import com.freshmarket.member.domain.service.kakao.KakaoUnlinkRetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 이벤트 리스너 어댑터라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoUnlinkEventListener {

    private static final int MAX_ATTEMPTS = 3;

    private final KakaoUnlinkClient kakaoUnlinkClient;
    private final KakaoUnlinkRetryService kakaoUnlinkRetryService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(MemberWithdrawalEvent event) {

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                kakaoUnlinkClient.unlink(event.kakaoUserId());
                return;
            } catch (Exception e) {
                boolean lastAttempt = attempt == MAX_ATTEMPTS;
                if (lastAttempt) {
                    // (2026-08-20, DI-6-02) 여기서 완전히 포기하면 DB는 탈퇴 상태, 카카오는 연결
                    // 유지 상태로 영구히 어긋날 수 있다 — kakao_unlink_failure에 기록해두고
                    // KakaoUnlinkRetryScheduler가 주기적으로 재시도하게 한다. 여기서 다시 예외를
                    // 던지지는 않는다(AFTER_COMMIT 리스너의 예외는 원 요청 스레드로 동기 전파돼
                    // 이미 끝난 탈퇴 요청을 500으로 만든다 — 탈퇴 자체는 이미 성공했다).
                    log.warn("event=KAKAO_UNLINK_RETRY_EXHAUSTED memberId={} kakaoUserId={} attempts={} — 아웃박스에 기록, 스케줄러가 재시도",
                            event.memberId(), PiiMasker.maskProviderId(event.kakaoUserId()), attempt, e);
                    kakaoUnlinkRetryService.recordFailure(event.memberId(), event.kakaoUserId());
                } else {
                    log.warn("event=KAKAO_UNLINK_RETRY memberId={} kakaoUserId={} attempt={}",
                            event.memberId(), PiiMasker.maskProviderId(event.kakaoUserId()), attempt, e);
                    // Thread.sleep으로 재시도 간격을 주지 않는다 — 이 스레드는 원 요청 스레드라
                    // 여기서 자면 그만큼 그 스레드가 다른 요청을 못 받는다.
                }
            }
        }
    }
}
