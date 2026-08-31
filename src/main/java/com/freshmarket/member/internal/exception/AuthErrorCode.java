package com.freshmarket.member.internal.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// (2026-08-18 12:10) docs/api/auth.md의 에러 표를 그대로 코드로 옮겼다. MemberErrorCode와
// 분리한 이유: 이 넷은 "회원 데이터가 이상하다"가 아니라 "로그인 프로토콜 단계에서 뭔가 실패했다"는
// 서로 다른 성격의 실패라, 별도 예외 타입(AuthException)으로 구분하는 게 호출부에서 catch하기 편하다.
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    STATE_MISMATCH(HttpStatus.UNAUTHORIZED, "AUTH-001", "state 또는 nonce가 일치하지 않습니다."),
    ID_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH-002", "id_token 검증에 실패했습니다."),
    KAKAO_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTH-003", "카카오 서버로부터 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH-004", "만료되었거나 이미 사용된 토큰입니다."),
    // docs/api/member.md의 탈퇴 절이 지정한 번호(401 AUTH-005) 그대로다. 재인증에 성공은
    // 했지만(id_token 검증 통과) 그 카카오 계정이 탈퇴를 요청한 회원 본인이 아닌 경우.
    REAUTH_ACCOUNT_MISMATCH(HttpStatus.UNAUTHORIZED, "AUTH-005", "재인증한 카카오 계정이 본인 계정과 다릅니다."),
    // (2026-08-27) MemberTokenService.revoke()가 DB 백업/Redis 정리 중 하나라도 실패하면 이 코드로
    // 던진다 — 재시도 아웃박스로 미루는 대신 로그아웃 응답 자체를 실패시켜 클라이언트가 재시도하게 한다.
    LOGOUT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "AUTH-006", "로그아웃 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
    ;

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
