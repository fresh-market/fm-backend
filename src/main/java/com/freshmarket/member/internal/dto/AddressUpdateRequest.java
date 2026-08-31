package com.freshmarket.member.internal.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// (2026-08-20, SEC-3-02/FUN-3-01) 전부 nullable — MemberProfileUpdateRequest와 같은 "보낸 필드만
// 바뀐다" 패턴. @Pattern/@Size는 Bean Validation 스펙상 null은 그냥 통과시키고 값이 있을 때만
// 형식을 검사하므로, null=미변경과 형식 검증이 같이 성립한다.
//
// recipient/phone은 조회 응답이 마스킹된 값만 내려주는 필드라(AddressResponse 참고), null 체크만으로는
// "폼에 남아있던 마스킹 표시값을 그대로 다시 보낸 경우"를 못 막는다 — 그건 AddressService.update()가
// PiiMasker.isMaskedEchoOf()로 한 번 더 걸러낸다.
//
// isDefault도 Boolean으로 바꿨다 — null/false 둘 다 "이 요청으로는 기본 배송지를 바꾸지 않는다"로
// 처리되므로(AddressService.update() 참고) 기존 boolean 시절 동작과 달라지는 게 없다.
//
// detailAddress처럼 원래도 선택 항목이던 필드는 이 방식으로는 "명시적으로 지운다"를 표현할 수
// 없다(null이 "미변경"과 "지움"을 둘 다 의미할 수 없어서) — MemberProfileUpdateRequest도 같은
// 한계를 이미 갖고 있다. 나중에 필요해지면 별도 처리(예: 빈 문자열을 "지움"으로 약속)를 검토한다.
public record AddressUpdateRequest(
        @Size(max = 50) String recipient,
        @Pattern(regexp = "01[016789]-?\\d{3,4}-?\\d{4}") String phone,
        @Pattern(regexp = "\\d{5}") String zipcode,
        @Size(max = 255) String roadAddress,
        @Size(max = 255) String detailAddress,
        Boolean isDefault
) {
}
