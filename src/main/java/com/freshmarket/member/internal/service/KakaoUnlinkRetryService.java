package com.freshmarket.member.internal.service;

import com.freshmarket.member.internal.client.KakaoUnlinkClient;
import com.freshmarket.member.internal.entity.KakaoUnlinkFailure;
import com.freshmarket.member.internal.repository.KakaoUnlinkFailureRepository;
import com.freshmarket.member.internal.repository.MemberRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카카오 unlink 아웃박스. 최초 unlink가 실패하면 WITHDRAWN_FAILED와 함께 기록하고,
 * KakaoUnlinkRetryScheduler가 매일 03시에 재시도한다.
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
    private final MemberRepository memberRepository;
    private final KakaoUnlinkClient kakaoUnlinkClient;
    private final KakaoUnlinkRetryOutcomeService outcomeService;

    @Transactional
    public void recordInitialFailure(Long memberId, String kakaoUserId) {
        memberRepository.markUnlinkFailed(memberId);
        failureRepository.findByMemberId(memberId).ifPresentOrElse(
                KakaoUnlinkFailure::markRetryFailed,
                () -> failureRepository.save(KakaoUnlinkFailure.record(memberId, kakaoUserId)));
    }

    /** WITHDRAWN_FAILED 회원의 미해소 unlink 실패를 매일 한 번씩 재시도한다. */
    public void retryAllPending() {
        for (KakaoUnlinkFailure failure : failureRepository.findByResolvedFalse()) {
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
