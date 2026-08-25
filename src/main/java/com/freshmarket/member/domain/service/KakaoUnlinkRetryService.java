package com.freshmarket.member.domain.service;

import com.freshmarket.member.domain.client.KakaoUnlinkClient;
import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import com.freshmarket.member.domain.repository.KakaoUnlinkFailureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * (2026-08-20, DI-6-02) 카카오 unlink 아웃박스. KakaoUnlinkEventListener의 즉시 재시도(3회)가
 * 다 실패하면 recordFailure()로 여기 남고, KakaoUnlinkRetryScheduler가 주기적으로
 * retryAllPending()을 불러 재시도한다.
 *
 * retryAllPending() 전체를 @Transactional로 묶지 않는다 — 그 안에서 카카오 호출(네트워크 대기)이
 * 일어나는데, 트랜잭션 안에서 동기 외부 호출을 하면 그 대기 동안 DB 커넥션이 묶인다(DI-4-02와
 * 같은 이유). 그래서 호출 결과 반영(성공 시 삭제/실패 시 카운트 증가)만 별도 빈
 * (KakaoUnlinkRetryOutcomeService)의 짧은 트랜잭션으로 각각 처리한다.
 */
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
        } catch (Exception e) {
            outcomeService.markFailed(failureId, e);
        }
    }
}
