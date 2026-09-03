package com.freshmarket.stock.internal.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// stock 도메인이 쓰는 오류 코드 모음
@Getter
@RequiredArgsConstructor
public enum StockErrorCode implements ErrorCode {

    EXPIRY_BEFORE_RECEIVED(HttpStatus.UNPROCESSABLE_CONTENT, "STOCK-001", "소비기한이 입고일보다 이릅니다."),
    /*
     * 두 군데서 쓴다: 입고 등록은 옵션이 그 상품 소속인지(existsOption)를, 로트별 조회는 상품
     * 자체가 있는지(옵션 ID 목록이 비어있는지)를 본다. 메시지는 "삭제된 상품"까지 말하지만, 실제로는
     * 존재 여부만 본다(상품 삭제 기능이 아직 없어 deleted_at이 채워질 방법이 없다).
     * 상품 삭제 기능이 생기면 그때 이 판정들도 손봐야 한다.
     */
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
    /*
     * 같은 로트를 동시에 건드리는 reserve/confirm/release끼리 경합한 경우 — 같은 주문상품을 동시에
     * 예약하는 요청끼리 uk_alloc_orderitem_lot에서 부딪히거나(호출부가 재시도하면 먼저 커밋된 쪽의
     * 할당을 findByOrderItemId로 찾아내 멱등하게 스킵한다), findByIdForUpdate가 락 대기 타임아웃이나
     * 교착으로 실패한 경우 모두 여기로 묶는다.
     */
    RESERVATION_IN_PROGRESS(HttpStatus.CONFLICT, "STOCK-009", "동일한 재고 요청이 아직 처리 중입니다. 잠시 후 다시 시도해주세요."),
    // stock.md에 폐기 이슈 전용으로 이미 예약돼 있는 코드
    DISPOSAL_QUANTITY_EXCEEDS_LOT(HttpStatus.UNPROCESSABLE_CONTENT, "STOCK-005", "폐기 수량이 로트 잔량을 넘습니다."),
    // 만료 배치의 조회 자체가 쓰기 락이라(StockLotRepository.findByStatusAndExpiryDateBefore) reserve()와 경합하면 여기로 묶는다
    EXPIRE_IN_PROGRESS(HttpStatus.CONFLICT, "STOCK-010", "만료 처리 중 다른 요청과 경합했습니다. 잠시 후 다시 시도해주세요."),
    LOT_NOT_FOUND(HttpStatus.NOT_FOUND, "STOCK-011", "없거나 삭제된 로트입니다."),
    // 같은 로트를 동시에 건드리는 reserve/confirm/release/expire와 폐기가 경합한 경우
    DISPOSAL_IN_PROGRESS(HttpStatus.CONFLICT, "STOCK-012", "동일한 로트에 대한 처리가 아직 진행 중입니다. 잠시 후 다시 시도해주세요."),
    /*
     * 캠페인 대상 확정이 이미 돌고 있는데 또 들어온 경우.
     *
     * 자정 스케줄과 관리자 재실행이 같은 확정 로직을 쓰므로 겹칠 수 있다. 확정은 그날 행을
     * 지우고 다시 넣는 작업이라 겹치면 uk_campaign_target_date_lot 이 뒤엣것을 거절한다.
     * 그 상황을 500 이 아니라 "지금은 안 되니 잠시 후" 로 알린다.
     */
    CAMPAIGN_REBUILD_IN_PROGRESS(HttpStatus.CONFLICT, "STOCK-014",
            "캠페인 대상 확정이 아직 진행 중입니다. 잠시 후 다시 시도해주세요."),
    /*
     * (CMP-4-04) 알려진 제약 위반이 아닌 저장 실패(로트 입고, 폐기 이력 저장)를 여기로 묶는다.
     * 원인(DB 예외 메시지)은 cause로만 유지해 로그에 남기고, 응답에는 이 고정 문구만 나간다 —
     * 내부 DB 오류 메시지를 그대로 클라이언트에 노출하지 않는다.
     */
    UNKNOWN_CONSTRAINT_VIOLATION(HttpStatus.INTERNAL_SERVER_ERROR, "STOCK-013", "저장 중 알 수 없는 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
