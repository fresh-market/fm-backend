package com.freshmarket.payment.internal.controller;

import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.internal.dto.PaymentConfirmRequest;
import com.freshmarket.payment.internal.dto.PaymentConfirmResponse;
import com.freshmarket.payment.internal.service.PaymentConfirmationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * [2026-09-27 KST] 토스 결제창이 successUrl로 프론트를 돌려보내면, 프론트가 그 결과(paymentKey 등)를
 * 들고 이 API를 호출해 실제 승인을 확정한다. PaymentApi가 아니라 PaymentConfirmationService를
 * 직접 주입한다 — 같은 payment 도메인의 컨트롤러이므로 공개 계약(PaymentApi)을 거칠 필요가 없다
 * (domain-package-boundary-guideline.md 참고). order 생성 시점에는 이 결제가 이미 준비돼 있어야
 * 하므로(order가 결제 요청 이벤트를 먼저 발행해 PENDING 행이 만들어져 있다), 이 컨트롤러는 그
 * 이후 "손님이 결제창에서 결제를 마쳤다"는 사실만 다룬다.
 */
@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Tag(name = "결제", description = "토스페이먼츠 결제 확정")
class PaymentConfirmController {

    private final PaymentConfirmationService paymentConfirmationService;

    @PostMapping("/confirm")
    @Operation(summary = "결제 확정", description = "토스 결제창 인증 결과를 받아 서버가 최종 승인(confirm)한다.")
    @ApiResponse(responseCode = "200", description = "확정 처리 완료(승인/거절/보류 여부는 status로 구분)")
    @ApiResponse(responseCode = "403", description = "본인 주문의 결제가 아님")
    @ApiResponse(responseCode = "409", description = "금액 불일치 또는 이미 다른 결제 시도가 진행 중")
    public ResponseEntity<ResponseEnvelope<PaymentConfirmResponse>> confirm(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody PaymentConfirmRequest request
    ) {
        PaymentResult result = paymentConfirmationService.confirm(
                request.orderId(), userDetails.getId(), request.amount(), request.paymentKey());
        return ResponseEntity.ok(ResponseEnvelope.success(PaymentConfirmResponse.from(result)));
    }
}
