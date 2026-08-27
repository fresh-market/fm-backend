package com.freshmarket.member.domain.controller;

import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.member.domain.service.profile.MemberProfileUpdateService;
import com.freshmarket.member.domain.dto.MemberProfileUpdateRequest;
import com.freshmarket.member.domain.dto.MemberResponse;
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
class MemberController {

    private final MemberProfileUpdateService memberProfileUpdateService;

    @GetMapping("/me")
    public ResponseEntity<ResponseEnvelope<MemberResponse>> getMyProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(ResponseEnvelope.success(memberProfileUpdateService.getMyProfile(userDetails.getId())));
    }

    @PatchMapping("/me")
    public ResponseEntity<ResponseEnvelope<MemberResponse>> updateProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid MemberProfileUpdateRequest request
    ) {
        return ResponseEntity.ok(ResponseEnvelope.success(memberProfileUpdateService.updateProfile(userDetails.getId(), request)));
    }
}
