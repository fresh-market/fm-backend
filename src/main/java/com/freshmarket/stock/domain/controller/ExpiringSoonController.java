package com.freshmarket.stock.domain.controller;

import static com.freshmarket.stock.domain.ExpiringSoonPolicy.DEFAULT_PAGE_SIZE;
import static com.freshmarket.stock.domain.ExpiringSoonPolicy.DEFAULT_WITHIN_DAYS;
import static com.freshmarket.stock.domain.ExpiringSoonPolicy.MAX_PAGE_SIZE;
import static com.freshmarket.stock.domain.ExpiringSoonPolicy.MAX_WITHIN_DAYS;

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

/*
 * 회원에게 소비기한 임박 상품 목록을 노출한다.
 *
 * 응답에 알 수 없는 필드가 추가되어도 클라이언트는 무시해야 한다 (CMP-3-01).
 * pageToken 은 목록/검색 조회와 같은 방식(PageTokens)으로 불투명화한다 (API-5-02).
 */
@RestController
@RequiredArgsConstructor
@Validated
class ExpiringSoonController {

    private final ExpiringSoonService expiringSoonService;

    @Operation(summary = "소비기한 임박 상품 조회",
            description = "로트의 소비기한 기준. 선착순 쿠폰 캠페인의 대상 선정과 같은 기준을 쓴다. "
                    + "productOptionId 오름차순 커서 페이지네이션. 응답에 알 수 없는 필드가 "
                    + "추가되어도 클라이언트는 이를 무시해야 한다.")
    @GetMapping("/v1/products:expiringSoon")
    public ResponseEntity<ResponseEnvelope<CursorPageResponse<ExpiringSoonResponse>>> getExpiringSoonProducts(
            @RequestParam(required = false, defaultValue = "" + DEFAULT_WITHIN_DAYS)
            @Positive @Max(MAX_WITHIN_DAYS) int withinDays,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE)
            @Positive @Max(MAX_PAGE_SIZE) int pageSize) {

        return ResponseEntity.ok(ResponseEnvelope.success(
                expiringSoonService.getExpiringSoonProducts(
                        withinDays, categoryId, pageToken, pageSize)));
    }
}
