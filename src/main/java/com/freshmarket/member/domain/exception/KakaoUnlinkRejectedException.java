package com.freshmarket.member.domain.exception;

/**
 * (2026-08-27, PR 리뷰 P1) 카카오가 unlink 요청을 4xx(429 제외)로 "정상적으로" 거절한 경우
 * 전용 예외 — 400 요청 오류, 401/403 Admin Key 오류, 404 이미 해제된 사용자 등.
 *
 * 이런 응답은 재시도해도 결과가 똑같으므로 일반 MemberException(KAKAO_UNLINK_FAILED)과
 * 같은 취급을 받으면 안 된다 — KakaoUnlinkEventListener/KakaoUnlinkRetryService가 이 타입을
 * 별도로 잡아서, 리스너 1회 + 스케줄러 최대 5회짜리 자동 재시도를 태우지 않고 즉시 포기
 * (수동 처리 대상) 상태로 넘긴다.
 */
public class KakaoUnlinkRejectedException extends MemberException {

    public KakaoUnlinkRejectedException(Throwable cause) {
        super(MemberErrorCode.KAKAO_UNLINK_REJECTED, cause);
    }
}
