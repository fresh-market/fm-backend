package com.freshmarket.member.domain;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.domain.client.KakaoUnlinkClient;
import com.freshmarket.member.domain.service.KakaoUnlinkRetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 이벤트 리스너 어댑터라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다.
 *
 * (2026-08-27) 예전엔 여기서 즉시 3회를 간격 없이 재시도했다 — AFTER_COMMIT 리스너라 원 요청
 * 스레드에서 동기로 도는데, 실패마다 매번 최대 8초(connect 3초+response 5초)씩 버려서 최악의
 * 경우 탈퇴 요청이 24초까지 묶일 수 있었다. unlink 실패는 사용자를 막는 문제가 아니라(탈퇴 자체는
 * 이미 커밋됨) 우리 쪽 뒷정리 문제라, 이 스레드에서 재시도로 몇 초 아끼려고 그 위험을 감수할
 * 이유가 없다고 판단해 1회만 시도하고 실패하면 바로 아웃박스로 넘긴다 — 실제 재시도는
 * KakaoUnlinkRetryScheduler가 전담한다(KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS 참고).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoUnlinkEventListener {

    private final KakaoUnlinkClient kakaoUnlinkClient;
    private final KakaoUnlinkRetryService kakaoUnlinkRetryService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(MemberWithdrawalEvent event) {
        try {
            kakaoUnlinkClient.unlink(event.kakaoUserId());
        } catch (Exception e) {
            log.warn("event=KAKAO_UNLINK_FAILED memberId={} kakaoUserId={} — 아웃박스에 기록, 스케줄러가 재시도",
                    event.memberId(), PiiMasker.maskProviderId(event.kakaoUserId()), e);
            kakaoUnlinkRetryService.recordFailure(event.memberId(), event.kakaoUserId());
        }
    }
}
