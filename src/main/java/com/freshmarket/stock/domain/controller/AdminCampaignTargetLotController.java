package com.freshmarket.stock.domain.controller;

import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.stock.domain.dto.CampaignTargetLotListResponse;
import com.freshmarket.stock.domain.service.AdminCampaignTargetLotService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 관리자용 캠페인 대상 로트 조회 API
@RestController
@RequestMapping("/v1/admin/campaigns")
class AdminCampaignTargetLotController {

    private final AdminCampaignTargetLotService adminCampaignTargetLotService;

    AdminCampaignTargetLotController(AdminCampaignTargetLotService adminCampaignTargetLotService) {
        this.adminCampaignTargetLotService = adminCampaignTargetLotService;
    }

    @Operation(summary = "캠페인 대상 로트 조회",
            description = "오늘 자정 배치가 확정한 캠페인 대상 로트를 조회한다. 계산은 하지 않고 확정본만 읽는다.")
    @GetMapping("/target-lots")
    public ResponseEntity<ResponseEnvelope<CampaignTargetLotListResponse>> findToday() {
        return ResponseEntity.ok(ResponseEnvelope.success(adminCampaignTargetLotService.findToday()));
    }
}
