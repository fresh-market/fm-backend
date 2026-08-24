package com.freshmarket.product.domain.batch;

import com.freshmarket.product.domain.entity.OptionAvailabilitySyncFailure;
import com.freshmarket.product.domain.repository.OptionAvailabilitySyncFailureRepository;
import com.freshmarket.product.domain.service.ProductOptionAvailabilityService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * (DI-6-01) 옵션 품절 여부 이벤트 아웃박스. OptionAvailabilityEventListener의 반영이 실패하면
 * recordFailure()로 여기 남고, OptionAvailabilitySyncScheduler가 주기적으로 retryAllPending()을
 * 불러 재시도한다. kakao_unlink_failure(KakaoUnlinkRetryService)와 같은 구조.
 *
 * retryAllPending()을 @Transactional로 묶지 않는다 — 재시도(updateSoldOut)와 결과 반영을 각각
 * 별도 트랜잭션으로 나눠서, 재시도 자체가 실패해도 실패 횟수 증가가 함께 롤백되지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class OptionAvailabilitySyncRetryService {

    private final OptionAvailabilitySyncFailureRepository failureRepository;
    private final ProductOptionAvailabilityService productOptionAvailabilityService;
    private final OptionAvailabilitySyncOutcomeService outcomeService;

    @Transactional
    public void recordFailure(Long productOptionId, boolean soldOut, LocalDateTime occurredAt) {
        failureRepository.findByProductOptionId(productOptionId).ifPresentOrElse(
                failure -> failure.overwriteWithNewerFailure(soldOut, occurredAt),
                () -> failureRepository.save(
                        OptionAvailabilitySyncFailure.record(productOptionId, soldOut, occurredAt)));
    }

    public void retryAllPending() {
        for (OptionAvailabilitySyncFailure failure : failureRepository.findAll()) {
            retryOne(failure.getId(), failure.getProductOptionId(), failure.isSoldOut(), failure.getOccurredAt());
        }
    }

    private void retryOne(Long failureId, Long productOptionId, boolean soldOut, LocalDateTime occurredAt) {
        try {
            productOptionAvailabilityService.updateSoldOut(productOptionId, soldOut, occurredAt);
            outcomeService.markSucceeded(failureId);
        } catch (Exception e) {
            outcomeService.markFailed(failureId, e);
        }
    }
}
