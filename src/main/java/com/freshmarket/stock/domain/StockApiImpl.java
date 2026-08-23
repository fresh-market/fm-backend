package com.freshmarket.stock.domain;

import static com.freshmarket.common.exception.ConstraintViolations.isConstraintViolation;

import com.freshmarket.stock.StockApi;
import com.freshmarket.stock.StockOrderItemsRequest;
import com.freshmarket.stock.StockReservationItemRequest;
import com.freshmarket.stock.StockReservationRequest;
import com.freshmarket.stock.domain.entity.AllocationStatus;
import com.freshmarket.stock.domain.entity.LotStatus;
import com.freshmarket.stock.domain.entity.StockAllocation;
import com.freshmarket.stock.domain.entity.StockLot;
import com.freshmarket.stock.domain.entity.StockMovement;
import com.freshmarket.stock.domain.exception.StockErrorCode;
import com.freshmarket.stock.domain.exception.StockException;
import com.freshmarket.stock.domain.repository.StockAllocationRepository;
import com.freshmarket.stock.domain.repository.StockLotRepository;
import com.freshmarket.stock.domain.repository.StockMovementRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

// StockApi 구현체. package-private로 감춰 다른 도메인이 이 클래스를 직접 참조하지 못하게 한다.
// ApiImpl에는 트랜잭션을 걸지 않는다(DPB-3-04, DPB-7-01) — 호출하는 도메인(주문/결제)이 자신의
// 트랜잭션 안에서 reserve/confirm/release를 호출해야, 이 메서드 전체가 그 트랜잭션의 일부로 묶인다
@Component
class StockApiImpl implements StockApi {

    private final StockLotRepository stockLotRepository;
    private final StockAllocationRepository stockAllocationRepository;
    private final StockMovementRepository stockMovementRepository;

    StockApiImpl(StockLotRepository stockLotRepository, StockAllocationRepository stockAllocationRepository,
            StockMovementRepository stockMovementRepository) {
        this.stockLotRepository = stockLotRepository;
        this.stockAllocationRepository = stockAllocationRepository;
        this.stockMovementRepository = stockMovementRepository;
    }

    /*
     * 주문상품마다 FEFO로 로트를 배분해 예약한다. 한 아이템이라도 끝까지 못 채우면 예외를 던진다 —
     * 호출부의 트랜잭션 안에서 실행되므로, 이 예외가 그 트랜잭션을 롤백시켜야 요청 안의 다른
     * 아이템이 이미 예약한 것까지 함께 되돌아간다(전체 성공 또는 전체 실패).
     * 재시도는 orderItemId 기준으로 먼저 조회해 걸러낸다 — 커밋된 적 없는 부분 예약은
     * 호출부 트랜잭션이 롤백되며 함께 사라지므로 남아있을 수 없다.
     */
    @Override
    public void reserve(StockReservationRequest request) {
        for (StockReservationItemRequest item : request.items()) {
            reserveItem(request.orderId(), item);
        }
    }

    private void reserveItem(Long orderId, StockReservationItemRequest item) {
        if (!stockAllocationRepository.findByOrderItemId(item.orderItemId()).isEmpty()) {
            return;
        }

        int remaining = item.qty();
        List<StockLot> lots = stockLotRepository.findByProductOptionIdAndStatusOrderByExpiryDateAsc(
                item.productOptionId(), LotStatus.AVAILABLE);

        for (StockLot lot : lots) {
            if (remaining <= 0) {
                break;
            }
            int beforeQty = lot.getAvailableQty();
            int attempt = Math.min(remaining, beforeQty);
            if (attempt <= 0) {
                continue;
            }
            // 조건부 UPDATE(stock.md). 영향받은 행이 0이면 그 사이 경합이 있었다는 뜻이라 재고 부족으로 처리한다
            if (stockLotRepository.decreaseAvailableQty(lot.getId(), attempt) == 0) {
                throw new StockException(StockErrorCode.INSUFFICIENT_STOCK);
            }
            saveAllocation(item.orderItemId(), lot.getId(), attempt);
            stockMovementRepository.save(StockMovement.reserve(lot.getId(), attempt, beforeQty, orderId));
            remaining -= attempt;
        }

        if (remaining > 0) {
            throw new StockException(StockErrorCode.INSUFFICIENT_STOCK);
        }
    }

    // uk_alloc_orderitem_lot 위반은 동시에 들어온 다른 reserve 재시도가 먼저 커밋한 경우다.
    // 이 트랜잭션은 실패로 두고 롤백시켜, 호출부가 재시도하면 findByOrderItemId로 그 결과를 그대로 본다
    private void saveAllocation(Long orderItemId, Long stockLotId, int qty) {
        try {
            stockAllocationRepository.save(StockAllocation.reserve(orderItemId, stockLotId, qty));
        } catch (DataIntegrityViolationException e) {
            if (isConstraintViolation(e, "uk_alloc_orderitem_lot")) {
                throw new StockException(StockErrorCode.RESERVATION_IN_PROGRESS, e);
            }
            throw e;
        }
    }

    /*
     * RESERVED 할당만 확정한다. status 조건으로 조회하기 때문에 이미 CONFIRMED/RELEASED인 건
     * 대상에서 자연히 빠져 재시도해도 다시 처리되지 않는다.
     */
    @Override
    public void confirm(StockOrderItemsRequest request) {
        List<StockAllocation> allocations = stockAllocationRepository.findByOrderItemIdInAndStatus(
                request.orderItemIds(), AllocationStatus.RESERVED);
        for (StockAllocation allocation : allocations) {
            allocation.confirm();
            int currentQty = getAvailableQty(allocation.getStockLotId());
            stockMovementRepository.save(
                    StockMovement.confirm(allocation.getStockLotId(), allocation.getQty(), currentQty,
                            request.orderId()));
        }
    }

    /*
     * RESERVED 할당만 해제한다. CONFIRMED는 findByOrderItemIdInAndStatus 조건에서 아예 빠지므로
     * 잘못 해제될 수 없다.
     */
    @Override
    public void release(StockOrderItemsRequest request) {
        List<StockAllocation> allocations = stockAllocationRepository.findByOrderItemIdInAndStatus(
                request.orderItemIds(), AllocationStatus.RESERVED);
        for (StockAllocation allocation : allocations) {
            allocation.release();
            int beforeQty = getAvailableQty(allocation.getStockLotId());
            stockLotRepository.increaseAvailableQty(allocation.getStockLotId(), allocation.getQty());
            stockMovementRepository.save(
                    StockMovement.release(allocation.getStockLotId(), allocation.getQty(), beforeQty,
                            request.orderId()));
        }
    }

    // 할당이 가리키는 로트는 fk_alloc_lot이 보장하므로 항상 존재한다
    private int getAvailableQty(Long stockLotId) {
        return stockLotRepository.findById(stockLotId).orElseThrow().getAvailableQty();
    }
}
