package com.freshmarket.member;

// 다른 도메인이 구독하는 회원 가입 완료 이벤트. member.domain 내부 타입을 노출하지 않는다.
public record MemberRegisteredEvent(Long memberId) {
}
