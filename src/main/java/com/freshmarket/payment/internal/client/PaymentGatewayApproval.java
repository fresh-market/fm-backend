package com.freshmarket.payment.internal.client;

import java.time.LocalDateTime;

public record PaymentGatewayApproval(
        String pgTid,
        LocalDateTime paidAt
) {
}
