package com.freshmarket.coupon.domain.controller;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.coupon.domain.dto.AdminMemberCouponListItem;
import com.freshmarket.coupon.domain.dto.AdminMemberCouponSearchCondition;
import com.freshmarket.coupon.domain.dto.MemberCouponHistoryResponse;
import com.freshmarket.coupon.domain.entity.MemberCouponStatus;
import com.freshmarket.coupon.domain.service.AdminCouponIssueQueryService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 관리자가 이미 나간 발급분을 조회하는 API. 쿠폰 하나의 발급 목록과 발급분 하나의 상태 전이 이력을 다룬다
@RestController
@Validated
class AdminCouponIssueHistoryController {

    // (CMP-3-03과 같은 이유) pageToken 길이 상한. AdminProductController와 같은 값
    private static final int MAX_PAGE_TOKEN_LENGTH = 500;
    // (SEC-3-03) status 길이 상한. MemberCouponStatus의 가장 긴 값(CANCELED)보다 넉넉히 잡는다
    private static final int MAX_STATUS_LENGTH = 20;

    private final AdminCouponIssueQueryService adminCouponIssueQueryService;

    AdminCouponIssueHistoryController(AdminCouponIssueQueryService adminCouponIssueQueryService) {
        this.adminCouponIssueQueryService = adminCouponIssueQueryService;
    }

    @Operation(summary = "쿠폰 발급 이력 목록",
            description = "이 쿠폰으로 나간 발급분을 상태로 걸러 커서 기반으로 조회한다.")
    @GetMapping("/v1/admin/coupons/{couponId}/issues")
    public ResponseEntity<ResponseEnvelope<CursorPageResponse<AdminMemberCouponListItem>>> findIssues(
            @PathVariable @Positive Long couponId,
            @RequestParam(required = false) @Size(max = MAX_STATUS_LENGTH) String status,
            @RequestParam(required = false) @Size(max = MAX_PAGE_TOKEN_LENGTH) String pageToken,
            @RequestParam(required = false, defaultValue = "" + AdminMemberCouponSearchCondition.DEFAULT_PAGE_SIZE)
                    int pageSize) {
        PageCursor cursor = PageTokens.decode(pageToken);
        AdminMemberCouponSearchCondition condition =
                new AdminMemberCouponSearchCondition(couponId, resolveStatus(status), cursor, pageSize);
        return ResponseEntity.ok(ResponseEnvelope.success(adminCouponIssueQueryService.findIssues(condition)));
    }

    // (CMP-3-03) 알 수 없는 값은 Spring의 불친절한 타입 변환 오류 대신 "필터 없음"으로 처리한다
    private MemberCouponStatus resolveStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return MemberCouponStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Operation(summary = "발급분 상태 이력", description = "이 발급분이 지금까지 거친 상태 전이를 순서대로 준다.")
    @GetMapping("/v1/admin/member-coupons/{memberCouponId}/history")
    public ResponseEntity<ResponseEnvelope<MemberCouponHistoryResponse>> findHistory(
            @PathVariable @Positive Long memberCouponId) {
        return ResponseEntity.ok(ResponseEnvelope.success(adminCouponIssueQueryService.findHistory(memberCouponId)));
    }
}
