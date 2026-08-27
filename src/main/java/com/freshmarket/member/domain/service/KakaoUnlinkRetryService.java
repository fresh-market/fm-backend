package com.freshmarket.member.domain.service;

import com.freshmarket.member.domain.client.KakaoUnlinkClient;
import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import com.freshmarket.member.domain.repository.KakaoUnlinkFailureRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * (2026-08-20, DI-6-02) 카카오 unlink 아웃박스. KakaoUnlinkEventListener의 1회 시도가 실패하면
 * recordFailure()로 여기 남고, KakaoUnlinkRetryScheduler가 주기적으로 retryAllPending()을 불러
 * 재시도한다.
 *
 * retryAllPending() 전체를 @Transactional로 묶지 않는다 — 그 안에서 카카오 호출(네트워크 대기)이
 * 일어나는데, 트랜잭션 안에서 동기 외부 호출을 하면 그 대기 동안 DB 커넥션이 묶인다(DI-4-02와
 * 같은 이유). 그래서 호출 결과 반영(성공 시 삭제/실패 시 카운트 증가)만 별도 빈
 * (KakaoUnlinkRetryOutcomeService)의 짧은 트랜잭션으로 각각 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoUnlinkRetryService {

    private final KakaoUnlinkFailureRepository failureRepository;
    private final KakaoUnlinkClient kakaoUnlinkClient;
    private final KakaoUnlinkRetryOutcomeService outcomeService;

    @Transactional
    public void recordFailure(Long memberId, String kakaoUserId) {
        failureRepository.findByMemberId(memberId).ifPresentOrElse(
                KakaoUnlinkFailure::markRetryFailed,
                () -> failureRepository.save(KakaoUnlinkFailure.record(memberId, kakaoUserId)));
    }

    /** 포기 문턱 미만의 미해소 행만 DB에서 조회해 재시도한다. */
    public void retryAllPending() {
        for (KakaoUnlinkFailure failure : failureRepository
                .findByAttemptCountLessThanAndResolvedFalse(KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS)) {
            retryOne(failure.getId(), failure.getKakaoUserId());
        }
    }

    private void retryOne(Long failureId, String kakaoUserId) {
        try {
            kakaoUnlinkClient.unlink(kakaoUserId);
            outcomeService.markSucceeded(failureId);
        } catch (CallNotPermittedException e) {
            /*
             * (2026-08-27) 서킷이 열려있어 카카오한테 물어보지도 못하고 튕겨나간 경우다.
             * markFailed()로 attemptCount를 올리면 "진짜로 5번 시도했는데도 실패"라는
             * MAX_RETRY_ATTEMPTS의 의미가 깨진다 — 카카오 장애가 길어지면 실제로는 한 번도
             * 제대로 물어보지 못한 채로 포기 처리(KAKAO_UNLINK_OUTBOX_GAVE_UP)될 수 있다.
             * 카운트는 그대로 두고 다음 스케줄러 사이클에서 다시 시도한다.
             */
            log.info("event=KAKAO_UNLINK_RETRY_SKIPPED_CIRCUIT_OPEN failureId={} — 카운트 증가 없이 다음 사이클로 넘김",
                    failureId);
        } catch (Exception e) {
            outcomeService.markFailed(failureId, e);
        }
    }
}
