package com.freshmarket.member.domain.event;

/**
 * DB 탈퇴 커밋 후에만 카카오 unlink를 호출하기 위한 이벤트.
 * 이벤트 페이로드라 서비스가 아니고, domain.service 패키지(커버리지 100% 대상)에 있으면 안 된다.
 */
public record MemberWithdrawalEvent(Long memberId, String kakaoUserId) {
}
