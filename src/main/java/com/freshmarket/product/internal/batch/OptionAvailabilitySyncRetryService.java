package com.freshmarket.product.internal.batch;

import com.freshmarket.product.internal.entity.OptionAvailabilitySyncFailure;
import com.freshmarket.product.internal.repository.OptionAvailabilitySyncFailureRepository;
import com.freshmarket.product.internal.service.ProductOptionAvailabilityService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    /*
     * markSucceeded/markFailed 자체의 실패를 updateSoldOut의 실패와 같은 catch로 묶지 않는다.
     * 같은 catch 안에 두면 동기화 자체는 성공했는데 markSucceeded()가 실패했을 때 markFailed()가
     * 불려 attemptCount가 잘못 올라가고(조기 포기를 앞당김), markFailed() 자체가 던지면 그 예외가
     * retryAllPending() 밖(@Scheduled)까지 전파돼 이번 주기의 나머지 대기 건 전부가 스킵된다.
     */
    private void retryOne(Long failureId, Long productOptionId, boolean soldOut, LocalDateTime occurredAt) {
        try {
            productOptionAvailabilityService.updateSoldOut(productOptionId, soldOut, occurredAt);
        } catch (Exception e) {
            safely(() -> outcomeService.markFailed(failureId, e), failureId);
            return;
        }
        safely(() -> outcomeService.markSucceeded(failureId), failureId);
    }

    private void safely(Runnable action, Long failureId) {
        try {
            action.run();
        } catch (Exception e) {
            // 한 건의 결과 반영 실패 때문에 배치 전체(나머지 대기 건)를 중단시키지 않는다
            log.error("event=OPTION_AVAILABILITY_SYNC_OUTCOME_FAILED failureId={}", failureId, e);
        }
    }
}
