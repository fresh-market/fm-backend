package com.freshmarket.stock.domain.controller;

import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.stock.domain.dto.AdminLotCreateRequest;
import com.freshmarket.stock.domain.dto.AdminLotResponse;
import com.freshmarket.stock.domain.service.AdminLotService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 관리자용 로트 입고 등록 API
@RestController
@RequestMapping("/v1/admin/products/{productId}/options/{optionId}/lots")
class AdminLotController {

    private final AdminLotService adminLotService;

    AdminLotController(AdminLotService adminLotService) {
        this.adminLotService = adminLotService;
    }

    @Operation(summary = "로트 입고 등록", description = "로트를 입고하고 INBOUND 변동 이력을 함께 남긴다.")
    @PostMapping
    public ResponseEntity<ResponseEnvelope<AdminLotResponse>> register(
            @PathVariable Long productId, @PathVariable Long optionId,
            @Valid @RequestBody AdminLotCreateRequest request) {
        AdminLotResponse response = adminLotService.register(productId, optionId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }
}
