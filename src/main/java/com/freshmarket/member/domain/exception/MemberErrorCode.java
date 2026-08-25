package com.freshmarket.member.domain.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * member 도메인 에러코드.
 *
 * (2026-08-18 12:50) docs/api/member.md의 에러 표 기준으로 번호를 다시 매겼다 — 001~004는
 * 문서가 명시한 그대로(탈퇴 시 진행 중 주문/환불, 배송지 권한/참조), 005는 문서상 관리자 도메인
 * 몫(회원 차단·해제 중복 처리)이라 지금은 admin 스코프가 아니라서 비워둔다. 006 이후는 문서에
 * 번호가 없는(내부적으로만 쓰이는) 기존 코드들을 그대로 옮겨 붙였다.
 *
 * MEMBER_HAS_ACTIVE_ORDER/MEMBER_HAS_PENDING_REFUND/ADDRESS_REFERENCED_BY_ORDER는 주문
 * 도메인이 아직 없어서 실제로 던지는 코드는 없다 — 나중에 주문 도메인이 생기면 그때 이 코드들을
 * 실제로 검사하는 로직을 붙인다(TODO 주석 참고).
 *
 * ADDRESS-001(구 AddressErrorCode.ADDRESS_NOT_FOUND)은 여기 흡수됐다 — docs/api/member.md가
 * 배송지 오류도 별도 prefix 없이 MEMBER- 코드로 다루고 있어서, 별도 예외 타입을 유지할 이유가
 * 없어졌다.
 *
 * (2026-08-18 15:10) 브랜치 전환 중 커밋 안 된 상태로 이 파일이 통째로 날아갔던 걸 복구함 —
 * 내용 변경 없이 그대로 다시 썼다.
 *
 * (2026-08-18 18:20) API 점검 중 번호 충돌을 발견해 내부 전용 코드들을 011부터로 밀었다 —
 * docs/api/member.md의 "등급 관리" 절이 MEMBER-006(소속 회원 있는 등급 삭제 시도)과
 * MEMBER-007(등급명 중복)을 이미 문서에 못 박아 뒀는데, 그 API가 아직 없어서 이 자리를
 * MEMBER_NOT_FOUND/MEMBER_ALREADY_WITHDRAWN이 먼저 차지하고 있었다. 그대로 두면 등급 관리
 * API를 만들 때 번호가 겹친다. 006/007은 그 API를 만들 때 채운다 — 지금은 미사용으로 비워 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

    // docs/api/member.md 명시 번호
    MEMBER_HAS_ACTIVE_ORDER(HttpStatus.CONFLICT, "MEMBER-001", "진행 중인 주문이 있어 탈퇴할 수 없습니다."),
    MEMBER_HAS_PENDING_REFUND(HttpStatus.CONFLICT, "MEMBER-002", "미완료 환불이 있어 탈퇴할 수 없습니다."),
    ADDRESS_FORBIDDEN(HttpStatus.FORBIDDEN, "MEMBER-003", "본인의 배송지가 아닙니다."),
    ADDRESS_REFERENCED_BY_ORDER(HttpStatus.CONFLICT, "MEMBER-004", "진행 중인 주문이 참조하는 배송지는 삭제할 수 없습니다."),
    // MEMBER-005는 admin 도메인 몫(회원 차단/해제 "이미 같은 상태다") — 지금 스코프 아님
    // MEMBER-006(등급 삭제 시 소속 회원 존재)/MEMBER-007(등급명 중복)은 "등급 관리" API를
    // 만들 때 채운다 — 문서가 이미 번호를 지정해 뒀으니 그때 그대로 쓰면 된다.

    // 문서에 번호가 명시되지 않은 내부 코드 (006/007과의 충돌을 피해 011부터 매김)
    MEMBER_NOT_FOUND(HttpStatus.BAD_REQUEST, "MEMBER-011", "회원을 찾을 수 없습니다."),
    MEMBER_ALREADY_WITHDRAWN(HttpStatus.BAD_REQUEST, "MEMBER-012", "이미 탈퇴한 회원입니다."),
    // MEMBER-013(구 DUPLICATE_NICKNAME)은 팀 결정으로 닉네임 유일성 요구사항 자체가 없어지면서
    // 지웠다(2026-08-19) — 재사용하지 않는다, 나중에 착각해서 다른 용도로 다시 쓰면 옛 로그의
    // MEMBER-013과 뜻이 섞인다.
    KAKAO_UNLINK_FAILED(HttpStatus.BAD_GATEWAY, "MEMBER-014", "카카오 연결 해제 요청에 실패했습니다."),
    DEFAULT_MEMBER_GRADE_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "MEMBER-015", "기본 회원 등급이 설정되어 있지 않습니다."),
    // (2026-08-20, FUN-3-03/FUN-3-04) docs/api/member.md에 배송지 등록 상한이 명시돼 있지 않아
    // 10개로 잡았다 — 문서가 나중에 다른 값을 못박으면 그 값으로 바꾼다.
    ADDRESS_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "MEMBER-016", "배송지는 최대 10개까지 등록할 수 있습니다."),
    KAKAO_UNLINK_FAILURE_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER-017", "카카오 연결 해제 실패 기록을 찾을 수 없습니다."),
    KAKAO_UNLINK_FAILURE_NOT_GAVE_UP(HttpStatus.CONFLICT, "MEMBER-018", "아직 포기 처리된 카카오 연결 해제 실패 기록이 아닙니다."),
    ;

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
