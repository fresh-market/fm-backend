package com.freshmarket.payment.internal.client;

import com.freshmarket.payment.PaymentRequest;

public interface PaymentGateway {

    PaymentGatewayApproval request(PaymentRequest request);
}
