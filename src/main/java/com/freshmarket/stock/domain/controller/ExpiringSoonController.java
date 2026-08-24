package com.freshmarket.stock.domain.controller;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.stock.domain.dto.ExpiringSoonResponse;
import com.freshmarket.stock.domain.service.ExpiringSoonService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 회원에게 소비기한 임박 상품 목록을 노출한다
@RestController
@RequiredArgsConstructor
@Validated
class ExpiringSoonController {

    private final ExpiringSoonService expiringSoonService;

    @Operation(summary = "소비기한 임박 상품 조회",
            description = "로트의 소비기한 기준. 선착순 쿠폰 캠페인의 대상 선정과 같은 기준을 쓴다. "
                    + "productOptionId 오름차순 커서 페이지네이션.")
    @GetMapping("/v1/products:expiringSoon")
    public ResponseEntity<ResponseEnvelope<CursorPageResponse<ExpiringSoonResponse>>> getExpiringSoonProducts(
            @RequestParam(required = false, defaultValue = "3") @Positive @Max(30) int withinDays,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false, defaultValue = "20") @Positive @Max(100) int pageSize) {

        Long cursorProductOptionId = pageToken != null ? Long.valueOf(pageToken) : null;

        return ResponseEntity.ok(ResponseEnvelope.success(
                expiringSoonService.getExpiringSoonProducts(
                        withinDays, categoryId, cursorProductOptionId, pageSize)));
    }
}