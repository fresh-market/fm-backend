package com.freshmarket.payment.domain.client;

import java.time.LocalDateTime;

public record PaymentGatewayApproval(
        String pgTid,
        LocalDateTime paidAt
) {
}
