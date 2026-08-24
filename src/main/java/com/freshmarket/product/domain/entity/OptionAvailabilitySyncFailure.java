package com.freshmarket.product.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * (DI-6-01) 옵션 품절 여부 이벤트(OptionAvailabilityChangedEvent)의 AFTER_COMMIT 반영이
 * 실패하면 이 행으로 남는다 — OptionAvailabilitySyncScheduler가 주기적으로 재시도하고, 성공하면
 * 행을 지운다. kakao_unlink_failure와 같은 구조(감사 이력이 아니라 "아직 처리 안 된 것"만
 * 담는 큐라, 처리 끝난 행을 남겨두지 않는다).
 */
@Entity
@Getter
@Table(name = "option_availability_sync_failure")
@AttributeOverride(name = "id", column = @Column(name = "option_availability_sync_failure_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OptionAvailabilitySyncFailure extends BaseMutableTimeEntity {

    private static final int MAX_RETRY_ATTEMPTS = 5;

    @Column(name = "product_option_id", nullable = false, unique = true)
    private Long productOptionId;

    // 마지막으로 반영을 시도했던 목표 값. 실패가 반복되는 동안 값이 또 바뀌면 최신 값으로 덮어쓴다
    @Column(name = "sold_out", nullable = false)
    private boolean soldOut;

    /*
     * (DI-2-01) 원래 이벤트가 발생한 시각(재시도 시각이 아니다). 재시도할 때도 이 값을 그대로 다시
     * 실어 보내야, product_option의 조건부 UPDATE가 순서를 올바르게 비교할 수 있다.
     */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    private OptionAvailabilitySyncFailure(Long productOptionId, boolean soldOut, LocalDateTime occurredAt) {
        validateProductOptionId(productOptionId);
        validateOccurredAt(occurredAt);
        this.productOptionId = productOptionId;
        this.soldOut = soldOut;
        this.occurredAt = occurredAt;
        this.attemptCount = 1;
    }

    // 리스너의 첫 반영 실패 때 쓰는 유일한 생성 진입점
    public static OptionAvailabilitySyncFailure record(Long productOptionId, boolean soldOut,
            LocalDateTime occurredAt) {
        return new OptionAvailabilitySyncFailure(productOptionId, soldOut, occurredAt);
    }

    // 이미 대기 중인 실패가 있는 옵션에 새 이벤트가 또 실패로 들어온 경우. 목표 값을 최신 이벤트로 덮어쓴다
    public void overwriteWithNewerFailure(boolean soldOut, LocalDateTime occurredAt) {
        validateOccurredAt(occurredAt);
        this.soldOut = soldOut;
        this.occurredAt = occurredAt;
        this.attemptCount++;
    }

    // 대기 중인 실패를 그대로 다시 시도했는데 또 실패한 경우. 새 이벤트가 아니라 목표 값은 그대로 둔다
    public void markRetryFailed() {
        this.attemptCount++;
    }

    public boolean shouldGiveUp() {
        return attemptCount >= MAX_RETRY_ATTEMPTS;
    }

    private static void validateProductOptionId(Long productOptionId) {
        if (productOptionId == null) {
            throw new IllegalArgumentException("productOptionId 는 필수다");
        }
    }

    private static void validateOccurredAt(LocalDateTime occurredAt) {
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt 은 필수다");
        }
    }
}
