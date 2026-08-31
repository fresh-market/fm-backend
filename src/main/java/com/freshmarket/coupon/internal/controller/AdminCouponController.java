package com.freshmarket.coupon.internal.controller;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.coupon.internal.dto.AdminCouponListItem;
import com.freshmarket.coupon.internal.dto.AdminCouponSearchCondition;
import com.freshmarket.coupon.internal.entity.CouponScope;
import com.freshmarket.coupon.internal.service.AdminCouponService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 관리자용 쿠폰 목록 조회 API. 생성, 활성화 등은 이어서 붙인다
@RestController
@Validated
class AdminCouponController {

    // (CMP-3-03과 같은 이유) pageToken 길이 상한. AdminProductController와 같은 값
    private static final int MAX_PAGE_TOKEN_LENGTH = 500;
    // (SEC-3-03) scope 길이 상한. CouponScope의 가장 긴 값(ORDER, ITEM)보다 넉넉히 잡는다
    private static final int MAX_SCOPE_LENGTH = 20;

    private final AdminCouponService adminCouponService;

    AdminCouponController(AdminCouponService adminCouponService) {
        this.adminCouponService = adminCouponService;
    }

    @Operation(summary = "쿠폰 목록 조회",
            description = "활성 여부와 적용 범위로 걸러 커서 기반으로 조회한다.")
    @GetMapping("/v1/admin/coupons")
    public ResponseEntity<ResponseEnvelope<CursorPageResponse<AdminCouponListItem>>> findAll(
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) @Size(max = MAX_SCOPE_LENGTH) String scope,
            @RequestParam(required = false) @Size(max = MAX_PAGE_TOKEN_LENGTH) String pageToken,
            @RequestParam(required = false, defaultValue = "" + AdminCouponSearchCondition.DEFAULT_PAGE_SIZE)
                    int pageSize) {
        PageCursor cursor = PageTokens.decode(pageToken);
        AdminCouponSearchCondition condition =
                new AdminCouponSearchCondition(isActive, resolveScope(scope), cursor, pageSize);
        return ResponseEntity.ok(ResponseEnvelope.success(adminCouponService.findAll(condition)));
    }

    // (CMP-3-03) 알 수 없는 값은 Spring의 불친절한 타입 변환 오류 대신 "필터 없음"으로 처리한다
    private CouponScope resolveScope(String scope) {
        if (scope == null) {
            return null;
        }
        try {
            return CouponScope.valueOf(scope);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
