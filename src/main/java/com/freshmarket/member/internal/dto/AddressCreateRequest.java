package com.freshmarket.member.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// (2026-08-20) AddressRequest를 생성/수정 두 용도로 같이 쓰다 보니, 수정 시 "안 바뀐 필드"를
// 표현할 방법이 없어 필드가 전부 필수였다 — SEC-3-02/FUN-3-01 정리 참고. 생성은 원래도 전부
// 필수라 이 분리로 달라지는 건 없고, AddressUpdateRequest만 nullable(=미변경)로 갈라졌다.
//
// 길이 제약은 docs/api/member.md의 배송지 필드 표를 그대로 따른다. phone/zipcode 형식 검증은
// 이번에 추가했다 — phone 정규식은 HttpBodyLoggingFilter.PHONE_PATTERN과 동일하게 맞춘다.
public record AddressCreateRequest(
        @NotBlank @Size(max = 50) String recipient,
        @NotBlank @Pattern(regexp = "01[016789]-?\\d{3,4}-?\\d{4}") String phone,
        @NotBlank @Pattern(regexp = "\\d{5}") String zipcode,
        @NotBlank @Size(max = 255) String roadAddress,
        @Size(max = 255) String detailAddress,
        boolean isDefault
) {
}
