package com.freshmarket.product.internal.batch;

import com.freshmarket.product.internal.repository.OptionAvailabilitySyncFailureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/*
 * (DI-6-01) OptionAvailabilitySyncRetryService.retryAllPending()이 재시도 "결과"만 반영하는
 * 전용 빈. 별도 빈으로 뺀 이유는 KakaoUnlinkRetryOutcomeService와 같다 — 재시도 자체가 실패해
 * 롤백된 트랜잭션 안에서 실패 횟수를 같이 늘리려 하면 그 증가분도 함께 롤백된다. 같은 클래스
 * 안에서 this.xxx()로 불러도 프록시를 안 거쳐 트랜잭션이 조용히 무시되므로, 다른 빈으로 뺀다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptionAvailabilitySyncOutcomeService {

    private final OptionAvailabilitySyncFailureRepository failureRepository;

    @Transactional
    public void markSucceeded(Long failureId) {
        failureRepository.deleteById(failureId);
    }

    @Transactional
    public void markFailed(Long failureId, Exception cause) {
        failureRepository.findById(failureId).ifPresent(failure -> {
            failure.markRetryFailed();
            if (failure.shouldGiveUp()) {
                // 여기부턴 실제 재고와 노출 상태가 계속 어긋난 채로 남는 것이라 사람이 봐야 한다
                log.error("event=OPTION_AVAILABILITY_SYNC_GAVE_UP productOptionId={} soldOut={} attempts={}",
                        failure.getProductOptionId(), failure.isSoldOut(), failure.getAttemptCount(), cause);
            } else {
                log.warn("event=OPTION_AVAILABILITY_SYNC_RETRY_FAILED productOptionId={} soldOut={} attempts={}",
                        failure.getProductOptionId(), failure.isSoldOut(), failure.getAttemptCount(), cause);
            }
        });
    }
}
