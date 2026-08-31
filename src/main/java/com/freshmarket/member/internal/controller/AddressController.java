package com.freshmarket.member.internal.controller;

import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.PageResponse;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.member.internal.entity.Address;
import com.freshmarket.member.internal.service.AddressService;
import com.freshmarket.member.internal.dto.AddressCreateRequest;
import com.freshmarket.member.internal.dto.AddressResponse;
import com.freshmarket.member.internal.dto.AddressUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// domain-map.md 기준 address는 member 도메인이 소유하는 테이블이라 member.domain.controller
// 아래에 둔다. docs/api/member.md가 경로를 /v1/members/me/addresses로, 수정 메서드를 PATCH로
// 명시한다(부분 수정이라 PUT이 아니라 PATCH).
// (2026-08-20, API-3-04/API-5-01) 목록 응답을 {"addresses": [...]}(AddressListResponse) 대신
// 공통 PageResponse로 바꿨다 — 배송지가 회원당 10개(등록 상한)라 지금은 페이지네이션이 실익이
// 없어 보이지만, 컬렉션 응답에 나중에 페이지네이션을 끼워 넣는 건 기존 클라이언트를 깨는 변경이라
// (api-design-guideline.md) 작은 목록이라도 처음부터 넣어 둔다. AddressListResponse는 더 이상
// 안 쓴다 — 삭제 권한 문제로 이 샌드박스에서 파일을 못 지웠으니 로컬에서 지워야 한다.
/** 회원 배송지 API. */
@RestController
@RequestMapping("/v1/members/me/addresses")
@RequiredArgsConstructor
@Tag(name = "배송지", description = "내 배송지 목록과 관리")
class AddressController {

    private final AddressService addressService;

    @GetMapping
    @Operation(summary = "내 배송지 목록 조회", description = "현재 회원의 배송지를 페이지 단위로 조회한다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    public ResponseEntity<ResponseEnvelope<PageResponse<AddressResponse>>> findMyAddresses(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Pageable pageable
    ) {
        var page = addressService.findMyAddresses(userDetails.getId(), pageable)
                .map(AddressResponse::from);
        return ResponseEntity.ok(ResponseEnvelope.success(PageResponse.from(page)));
    }

    @PostMapping
    @Operation(summary = "배송지 등록")
    @ApiResponse(responseCode = "201", description = "등록 성공")
    @ApiResponse(responseCode = "400", description = "요청 값이 유효하지 않음")
    public ResponseEntity<ResponseEnvelope<AddressResponse>> create(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid AddressCreateRequest request
    ) {
        Address address = addressService.create(userDetails.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(AddressResponse.from(address)));
    }

    @PatchMapping("/{addressId}")
    @Operation(summary = "배송지 수정")
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "404", description = "배송지가 없거나 현재 회원의 배송지가 아님")
    public ResponseEntity<ResponseEnvelope<AddressResponse>> update(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long addressId,
            @RequestBody @Valid AddressUpdateRequest request
    ) {
        Address address = addressService.update(userDetails.getId(), addressId, request);
        return ResponseEntity.ok(ResponseEnvelope.success(AddressResponse.from(address)));
    }

    @DeleteMapping("/{addressId}")
    @Operation(summary = "배송지 삭제")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "배송지가 없거나 현재 회원의 배송지가 아님")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long addressId
    ) {
        addressService.delete(userDetails.getId(), addressId);
        return ResponseEntity.noContent().build();
    }
}
