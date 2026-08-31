package com.freshmarket.member.internal;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.internal.client.KakaoLogoutClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 이벤트 리스너 어댑터라 서비스가 아니고, internal.service 패키지(커버리지 100% 대상)에 있으면 안 된다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoLogoutEventListener {

    private final KakaoLogoutClient kakaoLogoutClient;

    /*
     * (2026-08-27) KakaoLogoutClient.logout()이 서킷브레이커 대상이 되면서 더 이상 예외를
     * 삼키지 않는다 — 삼키면 CB가 실패를 못 봐서 서킷이 절대 안 열린다(KakaoCircuitBreakerConfig
     * 참고). 그래서 여기서 대신 받는다. unlink와 달리 로그아웃 실패는 아웃박스로 넘기지 않는다 —
     * 실패해도 우리 쪽 세션/토큰은 이미 정리된 뒤라 카카오 쪽 access token만 살아있는 정도고,
     * unlink만큼 데이터 정합성이 급한 문제가 아니라고 판단해 로그만 남기고 넘어간다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(MemberLogoutEvent event) {
        try {
            kakaoLogoutClient.logout(event.kakaoUserId());
        } catch (Exception e) {
            log.warn("event=KAKAO_LOGOUT_FAILED memberId={} kakaoUserId={} — 로그만 남기고 넘어감",
                    event.memberId(), PiiMasker.maskProviderId(event.kakaoUserId()), e);
        }
    }
}
