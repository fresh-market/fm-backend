package com.freshmarket.member.internal;

/**
 * 로그아웃 트랜잭션 커밋 후에만 카카오 세션 종료(logout)를 호출하기 위한 이벤트.
 * 카카오 호출을 @Transactional 밖으로 빼서 DB 커넥션/행 잠금이 카카오 응답 대기에 묶이지 않게 한다(REL-2-01,
 * DI-4-02). 이벤트 페이로드라 서비스가 아니고, internal.service 패키지(커버리지 100% 대상)에 있으면
 * 안 된다.
 */
public record MemberLogoutEvent(Long memberId, String kakaoUserId) {
}
