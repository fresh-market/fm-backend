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

    /*
     * [2026-09-05 18:28 KST] 복구 배치(PaymentReconciliationService)가 UNKNOWN을 뒤늦게 PAID로
     * 확정할 때도 이 메서드를 그대로 재사용한다 — PENDING에서의 최초 승인과 UNKNOWN에서의 뒤늦은
     * 확정은 "PG가 승인했다"는 같은 사실을 반영하는 것뿐이라 별도 메서드를 두지 않았다.
     */
    public void approve(String pgTid, LocalDateTime paidAt) {
        if (!isPending() && !isUnknown()) {
            throw new IllegalStateException("승인 대기 또는 UNKNOWN 상태의 결제만 승인할 수 있습니다.");
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
     * [2026-09-05 18:28 KST] PG가 명확히 거절한 경우의 전이. 재시도해도 같은 결과가 나오는 확정된
     * 실패이므로 FAILED로 확정한다. approve()와 같은 이유로 UNKNOWN에서도 허용한다 — 복구 배치가
     * PG 재조회 결과 "사실은 거절이었다"를 확정할 때도 이 메서드를 그대로 쓴다.
     */
    public void fail() {
        if (!isPending() && !isUnknown()) {
            throw new IllegalStateException("승인 대기 또는 UNKNOWN 상태의 결제만 실패로 전이할 수 있습니다.");
        }
        this.status = PaymentStatus.FAILED;
    }

    /*
     * [2026-09-05 18:28 KST] PG 응답이 timeout·연결 유실 등으로 결과를 알 수 없는 경우의 전이.
     * 실제로는 승인됐을 수도 있으므로 FAILED로 단정하지 않는다. 이후 PG 거래 조회(reconciliation)로
     * PAID 또는 FAILED로 재확정한다 — PENDING에서만 진입하고, UNKNOWN에서 다시 UNKNOWN으로 가는
     * 전이는 없다(PaymentService.markPaymentUnknown이 이미 UNKNOWN이면 호출 자체를 건너뛴다).
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
