package com.freshmarket.product.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    private OptionAvailabilitySyncFailure(Long productOptionId, boolean soldOut) {
        this.productOptionId = productOptionId;
        this.soldOut = soldOut;
        this.attemptCount = 1;
    }

    // 리스너의 첫 반영 실패 때 쓰는 유일한 생성 진입점
    public static OptionAvailabilitySyncFailure record(Long productOptionId, boolean soldOut) {
        return new OptionAvailabilitySyncFailure(productOptionId, soldOut);
    }

    public void markRetryFailed(boolean soldOut) {
        this.soldOut = soldOut;
        this.attemptCount++;
    }

    public boolean shouldGiveUp() {
        return attemptCount >= MAX_RETRY_ATTEMPTS;
    }
}
