package com.freshmarket.stock.internal.controller;

import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.stock.internal.dto.AdminLotCreateRequest;
import com.freshmarket.stock.internal.dto.AdminLotDisposeRequest;
import com.freshmarket.stock.internal.dto.AdminLotExpireResponse;
import com.freshmarket.stock.internal.dto.AdminLotListResponse;
import com.freshmarket.stock.internal.dto.AdminLotResponse;
import com.freshmarket.stock.internal.service.AdminLotService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 관리자용 로트 입고 등록, 조회, 폐기 처리, 만료 로트 일괄 처리 API. 경로가 서로 달라 클래스 레벨 매핑 없이 메서드마다 전체 경로를 둔다
@RestController
@Validated
class AdminLotController {

    // (SEC-3-03) pageToken 길이 상한. AdminProductController와 같은 근거·같은 값
    private static final int MAX_PAGE_TOKEN_LENGTH = 500;

    private final AdminLotService adminLotService;

    AdminLotController(AdminLotService adminLotService) {
        this.adminLotService = adminLotService;
    }

    @Operation(summary = "로트 입고 등록", description = "로트를 입고하고 INBOUND 변동 이력을 함께 남긴다.")
    @PostMapping("/v1/admin/products/{productId}/options/{optionId}/lots")
    public ResponseEntity<ResponseEnvelope<AdminLotResponse>> register(
            @PathVariable Long productId, @PathVariable Long optionId,
            @Valid @RequestBody AdminLotCreateRequest request) {
        AdminLotResponse response = adminLotService.register(productId, optionId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }

    @Operation(summary = "로트별 조회",
            description = "상품의 로트를 소비기한 오름차순(FEFO)으로 조회한다. 커서 기반으로 페이지네이션한다.")
    @GetMapping("/v1/admin/products/{productId}/lots")
    public ResponseEntity<ResponseEnvelope<AdminLotListResponse>> findAllByProduct(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "false") boolean availableOnly,
            @RequestParam(required = false) @Size(max = MAX_PAGE_TOKEN_LENGTH) String pageToken,
            @RequestParam(required = false, defaultValue = "0") int pageSize) {
        PageCursor cursor = PageTokens.decode(pageToken);
        AdminLotListResponse response = adminLotService.findAllByProduct(productId, availableOnly, cursor, pageSize);
        return ResponseEntity.ok(ResponseEnvelope.success(response));
    }

    @Operation(summary = "로트 폐기", description = "로트를 폐기 처리하고 DISPOSE 변동 이력을 함께 남긴다.")
    @PostMapping("/v1/admin/lots/{lotId}:dispose")
    public ResponseEntity<ResponseEnvelope<AdminLotResponse>> dispose(
            @PathVariable Long lotId,
            @AuthenticationPrincipal CustomUserDetails adminDetails,
            @Valid @RequestBody AdminLotDisposeRequest request) {
        AdminLotResponse response = adminLotService.dispose(lotId, adminDetails.getId(), request);
        return ResponseEntity.ok(ResponseEnvelope.success(response));
    }

    @Operation(summary = "만료 로트 일괄 처리",
            description = "소비기한이 지난 로트를 만료 처리하고 EXPIRE 이력을 남긴다. 하루 한 번 배치로 돌며, 이 경로는 수동 실행용이다. "
                    + "요청 전체가 원자적이지 않은 부분 성공 작업이다(API-3-10) — 중간에 실패해도 이미 처리된 로트는 그대로 남고, "
                    + "재요청하면 남은 대상만 이어서 처리된다(멱등).")
    @PostMapping("/v1/admin/lots:expire")
    public ResponseEntity<ResponseEnvelope<AdminLotExpireResponse>> expire() {
        AdminLotExpireResponse response = adminLotService.expireLots();
        return ResponseEntity.ok(ResponseEnvelope.success(response));
    }
}
