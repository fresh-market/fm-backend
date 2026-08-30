package com.freshmarket.coupon.domain.controller;

import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.coupon.domain.dto.CouponConsistencyCheckResponse;
import com.freshmarket.coupon.domain.service.CouponConsistencyService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * 관리자가 쿠폰 한 장의 정합성을 그 자리에서 확인하는 API.
 *
 * CouponConsistencyScheduler 의 새벽 배치는 300만 건 전체를 대상으로 하지만, 이 API는
 * 대상을 이 쿠폰 하나로 좁혀 관리자가 필요할 때 바로 확인할 수 있게 한다.
 */
@RestController
@Validated
class AdminCouponConsistencyController {

    private final CouponConsistencyService couponConsistencyService;

    AdminCouponConsistencyController(CouponConsistencyService couponConsistencyService) {
        this.couponConsistencyService = couponConsistencyService;
    }

    @Operation(summary = "쿠폰 정합성 검증",
            description = "발급 이력과 재고가 어긋나지 않는지 이 쿠폰 하나만 즉시 확인한다.")
    @PostMapping("/v1/admin/coupons/{couponId}:verifyConsistency")
    public ResponseEntity<ResponseEnvelope<CouponConsistencyCheckResponse>> verifyConsistency(
            @PathVariable @Positive Long couponId) {
        return ResponseEntity.ok(ResponseEnvelope.success(couponConsistencyService.verify(couponId)));
    }
}
