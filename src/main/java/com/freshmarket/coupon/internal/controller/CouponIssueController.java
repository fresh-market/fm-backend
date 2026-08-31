package com.freshmarket.coupon.internal.controller;

import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.coupon.internal.dto.CouponIssueResponse;
import com.freshmarket.coupon.internal.service.CouponIssueService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * 선착순 쿠폰 발급 API.
 *
 * 평범하게 블로킹한다. CompletableFuture 를 반환하는 비동기 컨트롤러와 견줬으나, 끊김 감지가
 * 필요 없어져 그쪽 이점이 사라졌다. 누가 왜 사라졌는지는 pending 이 시간으로 처리한다.
 * 남는 것은 스레드 점유뿐인데 그것은 가상 스레드가 푼다 (docs/coupon/coupon-v4.md 2장).
 */
@RestController
class CouponIssueController {

    private final CouponIssueService couponIssueService;

    CouponIssueController(CouponIssueService couponIssueService) {
        this.couponIssueService = couponIssueService;
    }

    @Operation(summary = "선착순 쿠폰 발급",
            description = "순번을 받아 발급한다. 이미 받은 회원은 같은 순번을 그대로 돌려받는다. "
                    + "소진은 둘로 갈린다. 미확정 순번을 쥔 사람이 있어 회수 여지가 있으면 409, "
                    + "쥔 사람도 없어 다시 나올 번호가 없으면 410 이다. "
                    + "몰려서 처리하지 못했으면 503 과 Retry-After 로 답한다.")
    @PostMapping("/v1/coupons/{couponId}/issues")
    public ResponseEntity<ResponseEnvelope<CouponIssueResponse>> issue(
            @PathVariable Long couponId,
            @AuthenticationPrincipal CustomUserDetails user) {
        CouponIssueResponse response = couponIssueService.issue(couponId, user.getId());
        return ResponseEntity.ok(ResponseEnvelope.success(response));
    }
}
