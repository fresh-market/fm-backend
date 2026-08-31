package com.freshmarket.coupon.domain.controller;

import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.coupon.domain.dto.CouponIssuanceStatusResponse;
import com.freshmarket.coupon.domain.service.CouponIssuanceStatusService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/*
 * 선착순 쿠폰의 발급 현황(총 수량/발급 수/잔여)을 회원에게 보여준다.
 *
 * CouponSecurityConfig가 이 경로를 따로 지목하지 않는다. 관리자 전용이 아니라 로그인한 회원이면
 * 누구나 볼 수 있어야 해서, 기본 체인(SecurityConfig.defaultFilterChain)의
 * anyRequest().authenticated()로 충분하다.
 */
@RestController
@Validated
class CouponIssuanceStatusController {

    private final CouponIssuanceStatusService couponIssuanceStatusService;

    CouponIssuanceStatusController(CouponIssuanceStatusService couponIssuanceStatusService) {
        this.couponIssuanceStatusService = couponIssuanceStatusService;
    }

    @Operation(summary = "쿠폰 발급 현황", description = "선착순 쿠폰의 총 수량, 발급 수, 잔여 수량을 준다.")
    @GetMapping("/v1/coupons/{couponId}/issuance-status")
    public ResponseEntity<ResponseEnvelope<CouponIssuanceStatusResponse>> findStatus(
            @PathVariable @Positive Long couponId) {
        return ResponseEntity.ok(ResponseEnvelope.success(couponIssuanceStatusService.findStatus(couponId)));
    }
}
