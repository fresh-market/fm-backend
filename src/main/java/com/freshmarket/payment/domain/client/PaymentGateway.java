package com.freshmarket.payment.domain.client;

import com.freshmarket.payment.PaymentRequest;

public interface PaymentGateway {

    PaymentGatewayApproval request(PaymentRequest request);
}
