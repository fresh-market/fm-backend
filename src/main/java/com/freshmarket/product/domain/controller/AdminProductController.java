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
import jakarta.validation.constraints.Positive;
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
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) SaleStatus saleStatus,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false, defaultValue = "" + AdminProductSearchCondition.DEFAULT_PAGE_SIZE)
                    int pageSize) {
        PageCursor cursor = PageTokens.decode(pageToken);
        AdminProductSearchCondition condition = new AdminProductSearchCondition(
                query, categoryId, saleStatus, includeDeleted, cursor, pageSize);
        return ResponseEntity.ok(ResponseEnvelope.success(adminProductService.findAll(condition)));
    }

    @Operation(summary = "상품 단건 조회", description = "삭제된 상품도 조회된다. 존재하지 않는 ID만 404다.")
    @GetMapping("/{productId}")
    public ResponseEntity<ResponseEnvelope<AdminProductResponse>> findById(
            @PathVariable @Positive Long productId) {
        return ResponseEntity.ok(ResponseEnvelope.success(adminProductService.findById(productId)));
    }
}
