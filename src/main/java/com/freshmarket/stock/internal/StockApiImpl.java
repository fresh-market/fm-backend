package com.freshmarket.stock.internal;

import com.freshmarket.stock.StockApi;
import com.freshmarket.stock.StockOrderItemsRequest;
import com.freshmarket.stock.StockReservationRequest;
import com.freshmarket.stock.internal.service.StockReservationService;
import org.springframework.stereotype.Component;

/*
 * StockApi 구현체. package-private로 감춰 다른 도메인이 이 클래스를 직접 참조하지 못하게 한다.
 * 요청 검증(null 체크)과 StockReservationService로의 위임만 한다 — 규칙 판단(FEFO 배분, 락,
 * 멱등성)은 전부 그 서비스에 있다(DPB-4-05).
 *
 * ApiImpl에는 트랜잭션을 걸지 않는다(DPB-3-04, DPB-7-01) — 호출하는 도메인(주문/결제)이 자신의
 * 트랜잭션 안에서 reserve/confirm/release를 호출해야, 이 메서드 전체가 그 트랜잭션의 일부로 묶인다
 */
@Component
class StockApiImpl implements StockApi {

    private final StockReservationService stockReservationService;

    StockApiImpl(StockReservationService stockReservationService) {
        this.stockReservationService = stockReservationService;
    }

    @Override
    public void reserve(StockReservationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 는 필수다");
        }
        stockReservationService.reserve(request);
    }

    @Override
    public void confirm(StockOrderItemsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 는 필수다");
        }
        stockReservationService.confirm(request);
    }

    @Override
    public void release(StockOrderItemsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 는 필수다");
        }
        stockReservationService.release(request);
    }
}
