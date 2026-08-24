package com.freshmarket.product.domain.controller;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.product.domain.dto.AdminProductCreateRequest;
import com.freshmarket.product.domain.dto.AdminProductListItem;
import com.freshmarket.product.domain.dto.AdminProductResponse;
import com.freshmarket.product.domain.dto.AdminProductSearchCondition;
import com.freshmarket.product.domain.entity.SaleStatus;
import com.freshmarket.product.domain.service.AdminProductService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 관리자용 상품 등록, 목록/단건 조회 API
@RestController
@RequestMapping("/v1/admin/products")
@Validated
class AdminProductController {

    // (SEC-3-03) categoryId 상한. auto_increment PK라 실제 값은 훨씬 작지만, 범위 없는 Long
    // 입력을 그대로 쿼리에 흘려보내지 않도록 상한을 둔다
    private static final long MAX_CATEGORY_ID = 999_999_999L;
    // (SEC-3-03) pageToken 길이 상한. 정상 토큰(prefix + base64(id + sortValue))은 수십 자
    // 수준이라 넉넉히 잡아도 충분하다 — 비정상적으로 긴 입력이 그대로 디코딩 시도되는 것을 막는다
    private static final int MAX_PAGE_TOKEN_LENGTH = 500;

    private final AdminProductService adminProductService;

    AdminProductController(AdminProductService adminProductService) {
        this.adminProductService = adminProductService;
    }

    @Operation(summary = "상품 등록", description = "상품과 판매 옵션을 함께 등록한다. 재고와 소비기한은 로트 입고로만 등록한다.")
    @PostMapping
    public ResponseEntity<ResponseEnvelope<AdminProductResponse>> register(
            @Valid @RequestBody AdminProductCreateRequest request) {
        AdminProductResponse response = adminProductService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }

    @Operation(summary = "상품 목록 조회",
            description = "판매안함, 품절, 삭제까지 전부 조회 대상이다. 재고 수량은 이 API 범위에 포함되지 않는다. "
                    + "커서 기반으로 페이지네이션한다.")
    @GetMapping
    public ResponseEntity<ResponseEnvelope<CursorPageResponse<AdminProductListItem>>> findAll(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) @Positive @Max(MAX_CATEGORY_ID) Long categoryId,
            @RequestParam(required = false) String saleStatus,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted,
            @RequestParam(required = false) @Size(max = MAX_PAGE_TOKEN_LENGTH) String pageToken,
            @RequestParam(required = false, defaultValue = "" + AdminProductSearchCondition.DEFAULT_PAGE_SIZE)
                    int pageSize) {
        PageCursor cursor = PageTokens.decode(pageToken);
        AdminProductSearchCondition condition = new AdminProductSearchCondition(
                query, categoryId, resolveSaleStatus(saleStatus), includeDeleted, cursor, pageSize);
        return ResponseEntity.ok(ResponseEnvelope.success(adminProductService.findAll(condition)));
    }

    /*
     * (CMP-3-03) saleStatus를 enum으로 직접 바인딩하면 알 수 없는 값이 Spring의 타입 변환
     * 오류(400, 메시지가 불친절함)로 이어진다. 문자열로 받아 직접 파싱해서, 값을 안 준 것과 같게
     * "필터 없음"으로 안전하게 기본 처리한다 — 다른 목록 필터(categoryId 등)도 생략하면 조건 없이
     * 전체를 보여주는 것과 같은 정책이다.
     */
    private SaleStatus resolveSaleStatus(String saleStatus) {
        if (saleStatus == null) {
            return null;
        }
        try {
            return SaleStatus.valueOf(saleStatus);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Operation(summary = "상품 단건 조회", description = "삭제된 상품도 조회된다. 존재하지 않는 ID만 404다.")
    @GetMapping("/{productId}")
    public ResponseEntity<ResponseEnvelope<AdminProductResponse>> findById(
            @PathVariable @Positive Long productId) {
        return ResponseEntity.ok(ResponseEnvelope.success(adminProductService.findById(productId)));
    }
}
