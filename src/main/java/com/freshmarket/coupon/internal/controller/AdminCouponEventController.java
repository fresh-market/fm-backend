package com.freshmarket.coupon.internal.controller;

import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.coupon.internal.dto.CouponIssuePeriodUpdateRequest;
import com.freshmarket.coupon.internal.service.CouponEventService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/*
 * 관리자가 선착순 이벤트를 열고 닫고 일정을 고치는 API.
 *
 * 셋 다 바꿀 수 있는 시점이 제한된다. 약속한 이벤트를 관리자가 도중에 흔들지 못하게 하려는
 * 것이고, 그 규칙이 서 있어야 앱이 자격 확인을 캐시할 수 있다
 * (docs/coupon/coupon.md 3장 "이벤트가 시작되면 조건이 얼어붙는다").
 */
@RestController
class AdminCouponEventController {

    private final CouponEventService couponEventService;

    AdminCouponEventController(CouponEventService couponEventService) {
        this.couponEventService = couponEventService;
    }

    @Operation(summary = "선착순 이벤트 열기",
            description = "Redis 카운터를 세우고 발급 스위치를 켠다. 이미 열려 있으면 아무것도 하지 않는다.")
    @PostMapping("/v1/admin/coupons/{couponId}/event:open")
    public ResponseEntity<ResponseEnvelope<Void>> open(@PathVariable Long couponId) {
        couponEventService.open(couponId);
        return ResponseEntity.ok(ResponseEnvelope.success(null));
    }

    @Operation(summary = "선착순 이벤트 닫기",
            description = "발급 스위치를 끄고, 발급 수를 실제 행 수로 맞추고, Redis 키를 지운다. "
                    + "마감 시각에서 60초가 지나야 끌 수 있다. 그 대기가 진행 중인 발급이 결판나는 시간이다. "
                    + "소진으로는 못 끈다. 스위치가 켜져 있어야 묶인 순번의 회수가 돈다.")
    @PostMapping("/v1/admin/coupons/{couponId}/event:close")
    public ResponseEntity<ResponseEnvelope<Void>> close(@PathVariable Long couponId) {
        couponEventService.close(couponId);
        return ResponseEntity.ok(ResponseEnvelope.success(null));
    }

    @Operation(summary = "발급 시각 변경",
            description = "아직 시작하지 않은 이벤트의 발급 시작과 종료 시각을 바꾼다. "
                    + "issue_start_at 이 지났으면 바꿀 수 없다.")
    @PatchMapping("/v1/admin/coupons/{couponId}/issue-period")
    public ResponseEntity<ResponseEnvelope<Void>> changeIssuePeriod(
            @PathVariable Long couponId,
            @Valid @RequestBody CouponIssuePeriodUpdateRequest request) {
        couponEventService.changeIssuePeriod(couponId, request.issueStartAt(), request.issueEndAt());
        return ResponseEntity.ok(ResponseEnvelope.success(null));
    }
}
