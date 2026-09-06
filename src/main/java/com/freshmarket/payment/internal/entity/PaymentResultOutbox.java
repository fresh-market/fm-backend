package com.freshmarket.payment.internal.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Payment의 최종 상태와 order 전달을 같은 트랜잭션에 남기는 outbox다. */
@Entity
@Table(name = "payment_result_outbox")
@AttributeOverride(name = "id", column = @Column(name = "payment_result_outbox_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentResultOutbox extends BaseMutableTimeEntity {

    @Column(name = "payment_id", nullable = false, unique = true)
    private Long paymentId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private PaymentResultOutboxType eventType;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "dispatched", nullable = false)
    private boolean dispatched;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    private PaymentResultOutbox(Long paymentId, Long orderId, PaymentResultOutboxType eventType,
            LocalDateTime paidAt, String failureReason) {
        if (paymentId == null || paymentId <= 0 || orderId == null || orderId <= 0 || eventType == null) {
            throw new IllegalArgumentException("결제 결과 outbox 식별자와 유형은 필수입니다.");
        }
        if (eventType == PaymentResultOutboxType.APPROVED && paidAt == null) {
            throw new IllegalArgumentException("승인 결과에는 paidAt이 필요합니다.");
        }
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.eventType = eventType;
        this.paidAt = paidAt;
        this.failureReason = failureReason;
    }

    public static PaymentResultOutbox approved(Long paymentId, Long orderId, LocalDateTime paidAt) {
        return new PaymentResultOutbox(paymentId, orderId, PaymentResultOutboxType.APPROVED, paidAt, null);
    }

    public static PaymentResultOutbox failed(Long paymentId, Long orderId, String failureReason) {
        return new PaymentResultOutbox(paymentId, orderId, PaymentResultOutboxType.FAILED, null, failureReason);
    }

    public void markDispatched(LocalDateTime dispatchedAt) {
        if (dispatched) {
            return;
        }
        if (dispatchedAt == null) {
            throw new IllegalArgumentException("dispatchedAt은 필수입니다.");
        }
        this.dispatched = true;
        this.dispatchedAt = dispatchedAt;
    }
}
