package com.freshmarket.member.domain.controller;

import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.PageResponse;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.member.domain.entity.Address;
import com.freshmarket.member.domain.service.AddressService;
import com.freshmarket.member.domain.dto.AddressCreateRequest;
import com.freshmarket.member.domain.dto.AddressResponse;
import com.freshmarket.member.domain.dto.AddressUpdateRequest;
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
class AddressController {

    private final AddressService addressService;

    @GetMapping
    public ResponseEntity<ResponseEnvelope<PageResponse<AddressResponse>>> findMyAddresses(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Pageable pageable
    ) {
        var page = addressService.findMyAddresses(userDetails.getId(), pageable)
                .map(AddressResponse::from);
        return ResponseEntity.ok(ResponseEnvelope.success(PageResponse.from(page)));
    }

    @PostMapping
    public ResponseEntity<ResponseEnvelope<AddressResponse>> create(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid AddressCreateRequest request
    ) {
        Address address = addressService.create(userDetails.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(AddressResponse.from(address)));
    }

    @PatchMapping("/{addressId}")
    public ResponseEntity<ResponseEnvelope<AddressResponse>> update(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long addressId,
            @RequestBody @Valid AddressUpdateRequest request
    ) {
        Address address = addressService.update(userDetails.getId(), addressId, request);
        return ResponseEntity.ok(ResponseEnvelope.success(AddressResponse.from(address)));
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long addressId
    ) {
        addressService.delete(userDetails.getId(), addressId);
        return ResponseEntity.noContent().build();
    }
}
