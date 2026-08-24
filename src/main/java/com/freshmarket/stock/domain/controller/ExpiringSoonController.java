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
import jakarta.validation.constraints.Pattern;
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
            // 커서는 내부적으로 productOptionId 를 그대로 쓴다. 양의 정수 문자열만 허용해
            // 형식이 다른 입력은 400 으로 명확히 거부한다 (SEC-3-01/02/03, FUN-3-01)
            @RequestParam(required = false)
            @Pattern(regexp = "^[1-9][0-9]{0,18}$", message = "pageToken 은 양의 정수여야 한다")
            String pageToken,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE)
            @Positive @Max(MAX_PAGE_SIZE) int pageSize) {

        Long cursorProductOptionId = pageToken != null ? Long.valueOf(pageToken) : null;

        return ResponseEntity.ok(ResponseEnvelope.success(
                expiringSoonService.getExpiringSoonProducts(
                        withinDays, categoryId, cursorProductOptionId, pageSize)));
    }
}
