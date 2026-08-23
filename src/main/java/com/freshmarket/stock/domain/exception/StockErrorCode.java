package com.freshmarket.stock.domain.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// stock 도메인이 쓰는 오류 코드 모음
@Getter
@RequiredArgsConstructor
public enum StockErrorCode implements ErrorCode {

    EXPIRY_BEFORE_RECEIVED(HttpStatus.UNPROCESSABLE_CONTENT, "STOCK-001", "소비기한이 입고일보다 이릅니다."),
    // 메시지는 "삭제된 상품"까지 말하지만, 실제로는 존재 여부만 본다(상품 삭제 기능이 아직 없어 deleted_at이 채워질 방법이 없다).
    // 상품 삭제 기능이 생기면 그때 이 판정에도 삭제 여부 확인을 추가해야 한다.
    OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "STOCK-002", "없거나 삭제된 상품입니다."),
    // STOCK-003~005는 재고 조정·폐기 이슈에서 쓰기로 stock.md에 이미 예약돼 있어 건너뛴다
    REGISTRATION_IN_PROGRESS(HttpStatus.CONFLICT, "STOCK-006", "동일한 요청이 아직 처리 중입니다. 잠시 후 다시 시도해주세요."),
    /*
     * request_id는 DB 전역에서 유일하다(uk_lot_request_id). 그런데 재시도 감지 조회는 (requestId,
     * optionId) 조합으로 스코프한다 — 그래야 같은 requestId가 다른 옵션에 잘못 재사용됐을 때 엉뚱한
     * 옵션의 로트를 재시도 응답으로 돌려주지 않는다. 그 결과, 같은 requestId를 다른 옵션에 다시 쓰면
     * 재시도로도 안 잡히고 DB 유니크 위반도 나는 진짜 충돌 상황이 생기는데, 그걸 여기로 구분한다.
     */
    REQUEST_ID_ALREADY_USED(HttpStatus.CONFLICT, "STOCK-007", "이미 다른 옵션에 사용된 요청 식별자입니다."),
    // FEFO로 가용 로트를 모두 훑어도 요청 수량을 채우지 못한 경우
    INSUFFICIENT_STOCK(HttpStatus.UNPROCESSABLE_CONTENT, "STOCK-008", "재고가 부족합니다."),
    // 같은 주문상품을 동시에 예약하는 요청끼리 uk_alloc_orderitem_lot에서 경합한 경우. 호출부가 재시도하면
    // 먼저 커밋된 쪽의 할당을 findByOrderItemId로 찾아내 멱등하게 스킵한다
    RESERVATION_IN_PROGRESS(HttpStatus.CONFLICT, "STOCK-009", "동일한 예약 요청이 아직 처리 중입니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
