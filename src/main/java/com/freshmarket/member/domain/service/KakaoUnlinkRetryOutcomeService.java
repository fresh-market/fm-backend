package com.freshmarket.member.domain.service;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import com.freshmarket.member.domain.repository.KakaoUnlinkFailureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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

    /**
     * (2026-08-27, PR 리뷰 P1) 카카오 4xx(429 제외) 거절 전용 — markFailed()처럼 attemptCount를
     * 한 칸씩 깎지 않고 markRejected()로 바로 포기 상태로 만든다. 재시도해도 같은 결과가
     * 반복될 실패를 굳이 5번 두드리게 할 이유가 없다.
     */
    @Transactional
    public void markRejected(Long failureId, Exception cause) {
        failureRepository.findById(failureId).ifPresent(failure -> {
            failure.markRejected();
            log.error("event=KAKAO_UNLINK_OUTBOX_REJECTED memberId={} kakaoUserId={} — 카카오 4xx 거절, "
                            + "재시도 없이 즉시 포기 처리",
                    failure.getMemberId(), PiiMasker.maskProviderId(failure.getKakaoUserId()), cause);
        });
    }

    @Transactional
    public void markFailed(Long failureId, Exception cause) {
        failureRepository.findById(failureId).ifPresent(failure -> {
            failure.markRetryFailed();
            String causeType = rootCauseType(cause);
            if (failure.shouldGiveUp()) {
                // (DI-6-02) 이 지점부턴 "조용히"가 아니다 — 우리 DB는 WITHDRAWN인데 카카오는
                // 연결이 살아있는 상태로 굳을 수 있는 컴플라이언스 문제라 사람이 봐야 한다.
                log.error("event=KAKAO_UNLINK_OUTBOX_GAVE_UP memberId={} kakaoUserId={} attempts={} causeType={}",
                        failure.getMemberId(), PiiMasker.maskProviderId(failure.getKakaoUserId()),
                        failure.getAttemptCount(), causeType, cause);
            } else {
                log.warn("event=KAKAO_UNLINK_OUTBOX_RETRY_FAILED memberId={} kakaoUserId={} attempts={} causeType={}",
                        failure.getMemberId(), PiiMasker.maskProviderId(failure.getKakaoUserId()),
                        failure.getAttemptCount(), causeType, cause);
            }
        });
    }

    /*
     * (2026-08-24) cause는 항상 KakaoUnlinkClient.unlink()가 던진 MemberException(KAKAO_UNLINK_FAILED)
     * 이라 메시지가 고정 문구("카카오 연결 해제 요청에 실패했습니다.")로 똑같다 — 실제 원인은
     * getCause()로 감싸진 원래 예외(WebClientResponseException 등) 안에 있는데, 그건 스택트레이스를
     * 펼쳐야만 보인다. 여기서 그 원인의 타입(+ HTTP 상태가 있으면 상태코드)만 요약해서 로그 줄
     * 자체에 필드로 얹는다 — 스택트레이스 첨부는 그대로 유지한 채(cause를 마지막 인자로 그대로
     * 넘기므로) 자세히 봐야 할 땐 여전히 전체 트레이스를 볼 수 있다.
     */
    private static String rootCauseType(Throwable cause) {
        Throwable root = cause.getCause() != null ? cause.getCause() : cause;
        if (root instanceof WebClientResponseException webClientException) {
            return webClientException.getStatusCode().value() + "_" + root.getClass().getSimpleName();
        }
        return root.getClass().getSimpleName();
    }
}
