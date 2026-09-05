package com.freshmarket.payment.internal.entity;

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

    /*
     * [2026-09-05 17:54 KST] PG가 명확히 거절한 경우의 전이. 재시도해도 같은 결과가 나오는 확정된
     * 실패이므로 FAILED로 확정한다. 지금은 PENDING에서만 허용한다 — UNKNOWN 상태에 놓인 결제를
     * 복구 배치가 FAILED로 확정하는 경로는 아직 없고, 그건 복구 배치 구현 시 별도로 다룬다.
     */
    public void fail() {
        if (!isPending()) {
            throw new IllegalStateException("승인 대기 상태의 결제만 실패로 전이할 수 있습니다.");
        }
        this.status = PaymentStatus.FAILED;
    }

    /*
     * [2026-09-05 17:54 KST] PG 응답이 timeout·연결 유실 등으로 결과를 알 수 없는 경우의 전이.
     * 실제로는 승인됐을 수도 있으므로 FAILED로 단정하지 않는다. 이후 PG 거래 조회(reconciliation)로
     * PAID 또는 FAILED로 재확정해야 한다 — 그 확정 경로 역시 복구 배치 구현 시 추가한다.
     */
    public void markUnknown() {
        if (!isPending()) {
            throw new IllegalStateException("승인 대기 상태의 결제만 UNKNOWN으로 전이할 수 있습니다.");
        }
        this.status = PaymentStatus.UNKNOWN;
    }

    public boolean isPaid() {
        return status == PaymentStatus.PAID;
    }

    public boolean isPending() {
        return status == PaymentStatus.PENDING;
    }

    public boolean isFailed() {
        return status == PaymentStatus.FAILED;
    }

    public boolean isUnknown() {
        return status == PaymentStatus.UNKNOWN;
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
