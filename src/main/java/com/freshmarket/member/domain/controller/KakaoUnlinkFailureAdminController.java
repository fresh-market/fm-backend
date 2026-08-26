package com.freshmarket.member.domain.controller;

import com.freshmarket.common.response.PageResponse;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.member.domain.dto.KakaoUnlinkFailureResponse;
import com.freshmarket.member.domain.service.kakao.KakaoUnlinkFailureResolutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 포기된 카카오 unlink 실패 건을 운영자가 해소 처리하는 API. */
@Tag(name = "카카오 unlink 실패 관리", description = "포기된 카카오 연결 해제 실패 건을 관리한다")
@RestController
@RequestMapping("/v1/admin/kakao-unlink-failures")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('TYPE_ADMIN') and hasRole('ADMIN')")
class KakaoUnlinkFailureAdminController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final KakaoUnlinkFailureResolutionService resolutionService;

    @Operation(summary = "카카오 unlink 포기 건 해소", description = "수동 조치가 끝난 포기 건을 리포트 대상에서 제외한다.")
    @ApiResponse(responseCode = "200", description = "해소 처리 성공")
    @ApiResponse(responseCode = "404", description = "실패 기록 없음 (MEMBER-017)")
    @ApiResponse(responseCode = "409", description = "아직 포기 상태가 아님 (MEMBER-018)")
    @PatchMapping("/{failureId}/resolve")
    ResponseEntity<ResponseEnvelope<Void>> resolve(@PathVariable Long failureId) {
        resolutionService.resolve(failureId);
        return ResponseEntity.ok(ResponseEnvelope.success());
    }

    @Operation(summary = "카카오 unlink 포기 건 조회", description = "아직 해소되지 않은 포기 건만 페이지로 조회한다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    ResponseEntity<ResponseEnvelope<PageResponse<KakaoUnlinkFailureResponse>>> getStuckFailures(
            @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
    ) {
        Pageable boundedPageable = PageRequest.of(
                pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE), pageable.getSort());
        Page<KakaoUnlinkFailureResponse> page = resolutionService.getStuckFailures(boundedPageable)
                .map(KakaoUnlinkFailureResponse::from);
        return ResponseEntity.ok(ResponseEnvelope.success(PageResponse.from(page)));
    }
}
