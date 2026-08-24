package com.freshmarket.payment;

// payment.method CHECK 제약과 같은 값만 외부 계약으로 공개한다.
public enum PaymentMethod {
    CARD,
    EASY_PAY
}
