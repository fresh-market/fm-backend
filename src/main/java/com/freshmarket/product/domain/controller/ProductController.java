package com.freshmarket.product.domain.controller;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.product.domain.dto.PageCursor;
import com.freshmarket.product.domain.dto.PageTokens;
import com.freshmarket.product.domain.dto.ProductDetailResponse;
import com.freshmarket.product.domain.dto.ProductListItem;
import com.freshmarket.product.domain.dto.ProductSearchCondition;
import com.freshmarket.product.domain.dto.ProductSortType;
import com.freshmarket.product.domain.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 회원에게 상품 목록 조회, 검색, 상세 조회를 노출한다. 관리자용은 AdminProductController 가 따로 맡는다
@RestController
@RequiredArgsConstructor
@Validated
class ProductController {

    private final ProductService productService;

    // 카테고리와 가격으로 거르고, 커서 기반으로 페이지네이션한다
    @Operation(summary = "상품 목록 조회",
            description = "카테고리와 가격 구간으로 거르고, 옵션 최저가/최신순으로 정렬하며, "
                    + "커서 기반으로 페이지네이션한다.")
    @GetMapping("/v1/products")
    public ResponseEntity<ResponseEnvelope<CursorPageResponse<ProductListItem>>> getProducts(
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) @PositiveOrZero Integer minPriceKrw,
            @RequestParam(required = false) @PositiveOrZero Integer maxPriceKrw,
            @RequestParam(required = false, defaultValue = "CREATED_DESC") ProductSortType sort,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false, defaultValue = "20") int pageSize) {

        ProductSearchCondition condition = buildCondition(
                categoryId, minPriceKrw, maxPriceKrw, null, sort, pageToken, pageSize);

        return ResponseEntity.ok(ResponseEnvelope.success(productService.getProducts(condition)));
    }

    // 상품명 부분 일치. 목록 조회와 같은 필터·정렬을 받고 검색어 조건만 추가된다 (API-3-08)
    @Operation(summary = "상품 검색",
            description = "상품명 부분 일치로 검색한다. 목록 조회와 같은 필터·정렬을 받는다.")
    @GetMapping("/v1/products:search")
    public ResponseEntity<ResponseEnvelope<CursorPageResponse<ProductListItem>>> searchProducts(
            @RequestParam @NotBlank @Size(max = 100) String query,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) @PositiveOrZero Integer minPriceKrw,
            @RequestParam(required = false) @PositiveOrZero Integer maxPriceKrw,
            @RequestParam(required = false, defaultValue = "CREATED_DESC") ProductSortType sort,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false, defaultValue = "20") int pageSize) {

        ProductSearchCondition condition = buildCondition(
                categoryId, minPriceKrw, maxPriceKrw, query, sort, pageToken, pageSize);

        return ResponseEntity.ok(ResponseEnvelope.success(productService.getProducts(condition)));
    }

    // 삭제된 상품은 404 로 응답한다
    @Operation(summary = "상품 상세 조회", description = "삭제된 상품은 404 로 응답한다.")
    @GetMapping("/v1/products/{productId}")
    public ResponseEntity<ResponseEnvelope<ProductDetailResponse>> getProductDetail(
            @PathVariable @Positive Long productId) {
        return ResponseEntity.ok(ResponseEnvelope.success(productService.getProductDetail(productId)));
    }

    // 목록 조회와 검색이 공유하는 조건 조립 로직 (MNT-3-01)
    private ProductSearchCondition buildCondition(Long categoryId, Integer minPriceKrw, Integer maxPriceKrw,
            String query, ProductSortType sort, String pageToken, int pageSize) {
        PageCursor cursor = PageTokens.decode(pageToken);
        return new ProductSearchCondition(
                categoryId, minPriceKrw, maxPriceKrw, query, sort, cursor, pageSize);
    }
}