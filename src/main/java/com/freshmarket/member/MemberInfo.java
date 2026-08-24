package com.freshmarket.member;

// 다른 도메인이 회원을 식별/표시할 때 필요한 읽기 전용 값이다. 인증 자체(JWT)는 이미 처리됐다는
// 전제라 provider/비밀번호류는 안 담는다 — 여기 없는 필드가 필요해지면 그때 추가한다.
//
// status를 MemberStatus(member.domain.entity) 그대로 안 내보내고 active로 미리 계산해서 준다 —
// 그대로 내보내면 호출부가 그 값을 쓰려고 다른 도메인의 domain 패키지를 직접 import해야 해서
// ArchitectureTest의 도메인 경계 규칙(도메인_내부는_다른_도메인에_닫혀_있다)을 어기게 된다
// (ProductOptionInfo.purchasable이 SaleStatus 대신 boolean을 주는 것과 같은 이유).
public record MemberInfo(
        Long memberId,
        String email,
        String name,
        boolean active
) {
}
