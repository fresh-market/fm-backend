package com.freshmarket.member.internal.controller;

import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.member.internal.service.MemberProfileUpdateService;
import com.freshmarket.member.internal.dto.MemberProfileUpdateRequest;
import com.freshmarket.member.internal.dto.MemberResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// docs/api/member.md 기준 경로는 /v1/members다. 온보딩 완료 로직은 별도 엔드포인트가 아니라
// MemberProfileUpdateService.updateProfile()의 "필수 항목이 다 채워지면 자동 전환" 분기로
// 흡수돼 있다(Member.updateProfile() 참고) — 문서가 PATCH /v1/members/me 하나만 정의한다.
/** 회원 프로필 API. */
@RestController
@RequestMapping("/v1/members")
@RequiredArgsConstructor
@Tag(name = "회원", description = "내 회원 정보 조회와 수정")
class MemberController {

    private final MemberProfileUpdateService memberProfileUpdateService;

    @GetMapping("/me")
    @Operation(summary = "내 회원 정보 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    public ResponseEntity<ResponseEnvelope<MemberResponse>> getMyProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(ResponseEnvelope.success(memberProfileUpdateService.getMyProfile(userDetails.getId())));
    }

    @PatchMapping("/me")
    @Operation(summary = "내 회원 정보 수정", description = "수정한 정보로 회원 프로필을 갱신한다. 필수 항목이 모두 채워지면 온보딩이 완료된다.")
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "400", description = "요청 값이 유효하지 않음")
    public ResponseEntity<ResponseEnvelope<MemberResponse>> updateProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid MemberProfileUpdateRequest request
    ) {
        return ResponseEntity.ok(ResponseEnvelope.success(memberProfileUpdateService.updateProfile(userDetails.getId(), request)));
    }
}
