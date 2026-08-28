package com.freshmarket.member.domain;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.domain.client.KakaoUnlinkClient;
import com.freshmarket.member.domain.exception.KakaoUnlinkRejectedException;
import com.freshmarket.member.domain.service.KakaoUnlinkRetryService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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

    /*
     * (2026-08-27) CallNotPermittedException(서킷 OPEN이라 카카오한테 요청 자체를 안 보낸 경우)을
     * 일반 실패와 분리해서 로그로 남긴다 — 이후 처리(recordFailure로 아웃박스 기록)는 동일하다.
     * 순수 로그 구분용이라 이 분리 자체가 재시도 동작을 바꾸진 않는다(카운트 관련 처리는
     * KakaoUnlinkRetryService.retryOne()에서 이미 갈라뒀다).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(MemberWithdrawalEvent event) {
        try {
            kakaoUnlinkClient.unlink(event.kakaoUserId());
        } catch (CallNotPermittedException e) {
            log.warn("event=KAKAO_UNLINK_CIRCUIT_OPEN memberId={} kakaoUserId={} — 호출 자체를 안 하고 아웃박스로",
                    event.memberId(), PiiMasker.maskProviderId(event.kakaoUserId()));
            kakaoUnlinkRetryService.recordFailure(event.memberId(), event.kakaoUserId());
        } catch (KakaoUnlinkRejectedException e) {
            // (2026-08-27, PR 리뷰 P1) 카카오가 4xx(429 제외)로 "정상적으로" 거절한 경우다 —
            // 재시도해도 결과가 같으므로 recordFailure()로 카운트를 하나씩 올리지 않고
            // recordRejected()로 바로 포기(수동 처리 대상) 상태로 넘긴다.
            log.error("event=KAKAO_UNLINK_REJECTED memberId={} kakaoUserId={} — 재시도 없이 즉시 수동 처리 대상",
                    event.memberId(), PiiMasker.maskProviderId(event.kakaoUserId()), e);
            kakaoUnlinkRetryService.recordRejected(event.memberId(), event.kakaoUserId());
        } catch (Exception e) {
            log.warn("event=KAKAO_UNLINK_FAILED memberId={} kakaoUserId={} — 아웃박스에 기록, 스케줄러가 재시도",
                    event.memberId(), PiiMasker.maskProviderId(event.kakaoUserId()), e);
            kakaoUnlinkRetryService.recordFailure(event.memberId(), event.kakaoUserId());
        }
    }
}
