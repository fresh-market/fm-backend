package com.freshmarket.stock.domain.controller;

import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.stock.domain.dto.AdminCampaignTargetLotListResponse;
import com.freshmarket.stock.domain.service.AdminCampaignTargetLotService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 관리자용 캠페인 대상 로트 조회 API
@RestController
@RequestMapping("/v1/admin/campaigns")
@Validated
class AdminCampaignTargetLotController {

    // (SEC-3-03) pageToken 길이 상한. AdminLotController 와 같은 근거·같은 값
    private static final int MAX_PAGE_TOKEN_LENGTH = 500;

    private final AdminCampaignTargetLotService adminCampaignTargetLotService;

    AdminCampaignTargetLotController(AdminCampaignTargetLotService adminCampaignTargetLotService) {
        this.adminCampaignTargetLotService = adminCampaignTargetLotService;
    }

    @Operation(summary = "캠페인 대상 로트 조회",
            description = "오늘 자정 배치가 확정한 캠페인 대상 로트를 소진율 오름차순으로 조회한다. "
                    + "계산은 하지 않고 확정본만 읽는다. 커서 기반으로 페이지네이션하며 "
                    + "페이지 크기는 기본 20, 최대 100 을 서버가 강제한다.")
    @GetMapping("/target-lots")
    public ResponseEntity<ResponseEnvelope<AdminCampaignTargetLotListResponse>> findToday(
            @RequestParam(required = false) @Size(max = MAX_PAGE_TOKEN_LENGTH) String pageToken,
            @RequestParam(required = false, defaultValue = "0") int pageSize) {
        PageCursor cursor = PageTokens.decode(pageToken);
        return ResponseEntity.ok(ResponseEnvelope.success(
                adminCampaignTargetLotService.find(cursor, pageSize)));
    }
}
