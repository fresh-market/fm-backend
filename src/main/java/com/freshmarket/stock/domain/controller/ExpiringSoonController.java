package com.freshmarket.stock.domain.controller;

import static com.freshmarket.stock.domain.ExpiringSoonPolicy.DEFAULT_PAGE_SIZE;
import static com.freshmarket.stock.domain.ExpiringSoonPolicy.MAX_PAGE_SIZE;

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
 * 회원에게 소비기한 임박 떨이 쿠폰 대상 상품을 노출한다.
 *
 * withinDays 파라미터가 없다. 대상 구간은 자정 배치가 확정할 때 이미 정해지고
 * (판매 마감 기한 D-10 ~ 임박 시작선 D-13), 이 API 는 그 확정본을 읽기만 하기 때문이다.
 * 회원이 구간을 넓히거나 좁힐 수 있으면 쿠폰 대상이 아닌 상품까지 섞여 나온다.
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
            description = "자정 배치가 확정한 그날의 떨이 쿠폰 대상 상품을 돌려준다. 관리자 조회와 같은 "
                    + "확정본을 읽으므로 같은 기준일에는 항상 같은 목록이다. 소진율 오름차순 커서 "
                    + "페이지네이션. 응답에 알 수 없는 필드가 추가되어도 클라이언트는 이를 무시해야 한다.")
    @GetMapping("/v1/products:expiringSoon")
    public ResponseEntity<ResponseEnvelope<CursorPageResponse<ExpiringSoonResponse>>> getExpiringSoonProducts(
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE)
            @Positive @Max(MAX_PAGE_SIZE) int pageSize) {

        return ResponseEntity.ok(ResponseEnvelope.success(
                expiringSoonService.getExpiringSoonProducts(categoryId, pageToken, pageSize)));
    }
}
