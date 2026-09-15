package com.freshmarket.payment;

// payment.status CHECK 제약과 같은 값만 외부 계약으로 공개한다.
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    UNKNOWN,
    CANCELED,
    REFUNDED
}
