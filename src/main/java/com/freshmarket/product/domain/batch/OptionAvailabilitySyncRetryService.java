package com.freshmarket.product.domain.batch;

import com.freshmarket.product.domain.entity.OptionAvailabilitySyncFailure;
import com.freshmarket.product.domain.repository.OptionAvailabilitySyncFailureRepository;
import com.freshmarket.product.domain.service.ProductOptionAvailabilityService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    private static final int PAGE_SIZE = 200;

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

    /*
     * (PERF-4-03) 미완료 건 전체를 findAll()로 한 번에 메모리에 올리지 않고 id 기준 keyset
     * 페이지네이션으로 나눠 처리한다. 페이지 안에서 성공한 행이 삭제돼도(markSucceeded) "id > 마지막
     * 처리 id" 조건이라 다음 페이지 조회가 밀리거나 건너뛰지 않는다.
     */
    public void retryAllPending() {
        Long afterId = 0L;
        List<OptionAvailabilitySyncFailure> page;
        Pageable pageable = PageRequest.of(0, PAGE_SIZE);
        do {
            page = failureRepository.findByIdGreaterThanAndAttemptCountLessThanOrderByIdAsc(
                    afterId, OptionAvailabilitySyncFailure.MAX_RETRY_ATTEMPTS, pageable);
            for (OptionAvailabilitySyncFailure failure : page) {
                retryOne(failure.getId(), failure.getProductOptionId(), failure.isSoldOut(), failure.getOccurredAt());
                afterId = failure.getId();
            }
        } while (!page.isEmpty());
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
