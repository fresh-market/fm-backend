package com.freshmarket.stock.domain.controller;

import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.stock.domain.dto.AdminLotCreateRequest;
import com.freshmarket.stock.domain.dto.AdminLotListResponse;
import com.freshmarket.stock.domain.dto.AdminLotResponse;
import com.freshmarket.stock.domain.service.AdminLotService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 관리자용 로트 입고 등록/조회 API
@RestController
@RequestMapping("/v1/admin/products/{productId}")
class AdminLotController {

    private final AdminLotService adminLotService;

    AdminLotController(AdminLotService adminLotService) {
        this.adminLotService = adminLotService;
    }

    @Operation(summary = "로트 입고 등록", description = "로트를 입고하고 INBOUND 변동 이력을 함께 남긴다.")
    @PostMapping("/options/{optionId}/lots")
    public ResponseEntity<ResponseEnvelope<AdminLotResponse>> register(
            @PathVariable Long productId, @PathVariable Long optionId,
            @Valid @RequestBody AdminLotCreateRequest request) {
        AdminLotResponse response = adminLotService.register(productId, optionId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }

    @Operation(summary = "로트별 조회", description = "상품의 로트 전체를 소비기한 오름차순(FEFO)으로 조회한다.")
    @GetMapping("/lots")
    public ResponseEntity<ResponseEnvelope<AdminLotListResponse>> findAllByProduct(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "false") boolean availableOnly) {
        AdminLotListResponse response = adminLotService.findAllByProduct(productId, availableOnly);
        return ResponseEntity.ok(ResponseEnvelope.success(response));
    }
}
