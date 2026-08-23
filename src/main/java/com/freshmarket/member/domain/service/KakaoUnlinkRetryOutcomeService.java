package com.freshmarket.member.domain.service;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import com.freshmarket.member.domain.repository.KakaoUnlinkFailureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * (2026-08-20, DI-6-02) KakaoUnlinkRetryService.retryAllPending()이 카카오를 호출한 "결과"만
 * DB에 반영하는 전용 빈. 별도 빈으로 뺀 이유는 MemberWithdrawalCompletionService와 같다 — 같은
 * 클래스 안에서 this.xxx()로 @Transactional 메서드를 불러봐야 프록시를 안 거쳐서 트랜잭션이
 * 조용히 무시된다(Spring AOP 자기 자신 호출 한계). retryAllPending()이 이 클래스의 메서드를
 * 부르는 건 다른 빈을 부르는 거라 정상적으로 프록시를 탄다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoUnlinkRetryOutcomeService {

    private final KakaoUnlinkFailureRepository failureRepository;

    @Transactional
    public void markSucceeded(Long failureId) {
        failureRepository.deleteById(failureId);
    }

    @Transactional
    public void markFailed(Long failureId, Exception cause) {
        failureRepository.findById(failureId).ifPresent(failure -> {
            failure.markRetryFailed();
            if (failure.shouldGiveUp()) {
                // (DI-6-02) 이 지점부턴 "조용히"가 아니다 — 우리 DB는 WITHDRAWN인데 카카오는
                // 연결이 살아있는 상태로 굳을 수 있는 컴플라이언스 문제라 사람이 봐야 한다.
                log.error("event=KAKAO_UNLINK_OUTBOX_GAVE_UP memberId={} kakaoUserId={} attempts={}",
                        failure.getMemberId(), PiiMasker.maskProviderId(failure.getKakaoUserId()),
                        failure.getAttemptCount(), cause);
            } else {
                log.warn("event=KAKAO_UNLINK_OUTBOX_RETRY_FAILED memberId={} kakaoUserId={} attempts={}",
                        failure.getMemberId(), PiiMasker.maskProviderId(failure.getKakaoUserId()),
                        failure.getAttemptCount(), cause);
            }
        });
    }
}
