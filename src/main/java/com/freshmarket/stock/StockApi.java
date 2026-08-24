package com.freshmarket.stock;

// 다른 도메인(주문/결제)이 재고를 예약·확정·해제할 때 쓰는 공개 창구
public interface StockApi {

    // FEFO로 로트를 배분해 가용 수량을 차감하고 RESERVED 할당을 만든다. 하나라도 부족하면 전체 롤백
    void reserve(StockReservationRequest request);

    // RESERVED 할당을 CONFIRMED로 바꾼다. 가용 수량은 예약 시점에 이미 빠졌으므로 건드리지 않는다
    void confirm(StockOrderItemsRequest request);

    // RESERVED 할당만 RELEASED로 바꾸고 가용 수량을 복원한다. CONFIRMED는 대상이 아니다
    void release(StockOrderItemsRequest request);
}
