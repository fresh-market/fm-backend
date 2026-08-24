package com.freshmarket.payment.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentStatus;
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

@Entity
@Table(name = "payment")
@AttributeOverride(name = "id", column = @Column(name = "payment_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseMutableTimeEntity {

    private static final int PG_TID_MAX_LENGTH = 100;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 30)
    private PaymentMethod method;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "refunded_amount", nullable = false)
    private int refundedAmount;

    @Column(name = "pg_tid", length = PG_TID_MAX_LENGTH)
    private String pgTid;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    private Payment(Long orderId, PaymentMethod method, int amount) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId 는 필수다");
        }
        if (method == null) {
            throw new IllegalArgumentException("method 는 필수다");
        }
        if (amount < 1) {
            throw new IllegalArgumentException("amount 는 1 이상이어야 한다: " + amount);
        }
        this.orderId = orderId;
        this.method = method;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
        this.refundedAmount = 0;
    }

    public static Payment prepare(Long orderId, PaymentMethod method, int amount) {
        return new Payment(orderId, method, amount);
    }

    public void approve(String pgTid, LocalDateTime paidAt) {
        if (!isPending()) {
            throw new IllegalStateException("승인 대기 상태의 결제만 승인할 수 있습니다.");
        }
        if (pgTid == null || pgTid.isBlank() || pgTid.length() > PG_TID_MAX_LENGTH) {
            throw new IllegalArgumentException("유효한 pgTid 가 필요하다");
        }
        if (paidAt == null) {
            throw new IllegalArgumentException("paidAt 은 필수다");
        }
        this.pgTid = pgTid;
        this.paidAt = paidAt;
        this.status = PaymentStatus.PAID;
    }

    public boolean isPaid() {
        return status == PaymentStatus.PAID;
    }

    public boolean isPending() {
        return status == PaymentStatus.PENDING;
    }

    public boolean matches(PaymentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("payment request 는 필수다");
        }
        return orderId.equals(request.orderId())
                && amount == request.amount()
                && method == request.method();
    }

    public PaymentRequest toRequest() {
        return new PaymentRequest(orderId, amount, method);
    }
}
