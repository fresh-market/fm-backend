package com.freshmarket.order.internal.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 생성 트랜잭션과 결제 요청 전달을 원자적으로 묶는 outbox다.
 *
 * <p>발행 성공 뒤 dispatched를 표시한다. 그 표시 전에 프로세스가 죽으면 같은 이벤트가 다시
 * 전달될 수 있지만, payment의 orderId 단위 멱등 처리로 한 결제로 수렴한다.</p>
 */
@Entity
@Table(name = "order_payment_request_outbox")
@AttributeOverride(name = "id", column = @Column(name = "order_payment_request_outbox_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderPaymentRequestOutbox extends BaseMutableTimeEntity {

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Column(name = "dispatched", nullable = false)
    private boolean dispatched;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    private OrderPaymentRequestOutbox(Long orderId, int amount) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("orderId는 0보다 커야 합니다.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("결제 요청 금액은 0보다 커야 합니다.");
        }
        this.orderId = orderId;
        this.amount = amount;
    }

    public static OrderPaymentRequestOutbox pending(Long orderId, int amount) {
        return new OrderPaymentRequestOutbox(orderId, amount);
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
