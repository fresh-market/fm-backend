package com.freshmarket.member.internal.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// (2026-08-18 13:25) docs/api/member.md 필드 표 그대로: 부분 수정이라 전부 선택 필드다("보낸
// 필드만 바뀐다"). marketingAgreed는 false와 "안 보냄"을 구분해야 해서 boolean이 아니라
// Boolean(null 허용)으로 뒀다. address는 문서 표에 없어 뺐다(배송지는 별도 Address API).
// (2026-08-18 15:10) 브랜치 전환 중 커밋 안 된 상태로 이 파일이 통째로 날아갔던 걸 복구함 —
// 내용 변경 없이 그대로 다시 썼다.
// (2026-08-20, SEC-3-02) phone에 형식 검증을 추가한다 — 정규식은 HttpBodyLoggingFilter.PHONE_PATTERN과
// 동일하게 맞춘다. Member.updateProfile()이 빈 문자열("")을 "전화번호를 지운다"는 의미로 쓰고
// 있어서(phone.isBlank() ? null : phone), 그 관례를 깨지 않게 "^$|<번호형식>"으로 빈 문자열도
// 허용한다 — @Pattern은 null은 통과시키지만 빈 문자열은 형식 검사 대상으로 보기 때문에, 이
// 예외를 명시하지 않으면 "지우기"가 400으로 막힌다.
public record MemberProfileUpdateRequest(
        @Size(max = 50) String name,
        @Size(max = 50) String nickname,
        @Email @Size(max = 255) String email,
        @Pattern(regexp = "^$|01[016789]-?\\d{3,4}-?\\d{4}") String phone,
        Boolean marketingAgreed
) {
}
