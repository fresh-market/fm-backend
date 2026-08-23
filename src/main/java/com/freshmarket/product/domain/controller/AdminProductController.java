package com.freshmarket.product.domain.controller;

import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.product.domain.dto.AdminProductCreateRequest;
import com.freshmarket.product.domain.dto.AdminProductResponse;
import com.freshmarket.product.domain.service.AdminProductService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 관리자용 상품 등록 API
@RestController
@RequestMapping("/v1/admin/products")
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
}
