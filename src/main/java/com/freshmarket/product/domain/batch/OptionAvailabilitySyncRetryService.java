package com.freshmarket.product.domain.batch;

import com.freshmarket.product.domain.entity.OptionAvailabilitySyncFailure;
import com.freshmarket.product.domain.repository.OptionAvailabilitySyncFailureRepository;
import com.freshmarket.product.domain.service.ProductOptionAvailabilityService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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

    // (FUN-3-04) 한 주기에 처리할 상한. 서버가 강제하는 값이라 적체가 커져도 배치 실행 시간이 늘어나지 않는다
    private static final int RETRY_CHUNK_SIZE = 200;

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
     * (FUN-3-03/DI-4-03/PERF-4-01/PERF-4-03) findAll()로 전체를 한 번에 올리지 않고 id 청크로 나눠 처리한다.
     * id 기준으로 전진해서, 청크 처리 중 성공한 행이 지워져도(markSucceeded) 다음 청크가 밀리지 않는다.
     * (REL-2-07) 재시도 한도를 넘어 exhausted 로 남은 행은 건너뛴다 — 지우지는 않되 무한 재시도를 막는다.
     */
    public void retryAllPending() {
        long lastId = 0L;
        List<OptionAvailabilitySyncFailure> chunk;
        do {
            chunk = failureRepository.findByIdGreaterThanOrderByIdAsc(lastId, PageRequest.of(0, RETRY_CHUNK_SIZE));
            for (OptionAvailabilitySyncFailure failure : chunk) {
                if (!failure.isExhausted()) {
                    retryOne(failure.getId(), failure.getProductOptionId(), failure.isSoldOut(),
                            failure.getOccurredAt());
                }
                lastId = failure.getId();
            }
        } while (chunk.size() == RETRY_CHUNK_SIZE);
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
