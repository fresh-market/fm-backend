package com.freshmarket.stock.domain.service;

import static com.freshmarket.common.exception.ConstraintViolations.isConstraintViolation;

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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/*
 * 재고 예약/확정/해제의 실제 규칙 판단(FEFO 배분, 조건부 UPDATE, 락, 멱등성)을 담당한다.
 * StockApiImpl은 요청 검증(null 체크)과 이 서비스로의 위임만 한다(DPB-4-05).
 *
 * 트랜잭션을 걸지 않는다 — StockApiImpl과 같은 이유(DPB-3-04, DPB-7-01)로, 호출부(주문/결제
 * 도메인)의 트랜잭션 경계를 그대로 이어받아야 한다. 이 서비스는 ApiImpl 뒤에서만 호출되므로
 * 여기서 트랜잭션을 열면 경계 선언이 두 곳으로 갈린다.
 */
@Service
public class StockReservationService {

    private final StockLotRepository stockLotRepository;
    private final StockAllocationRepository stockAllocationRepository;
    private final StockMovementRepository stockMovementRepository;

    public StockReservationService(StockLotRepository stockLotRepository,
            StockAllocationRepository stockAllocationRepository,
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
    public void reserve(StockReservationRequest request) {
        if (request.items() == null) {
            return;
        }
        for (StockReservationItemRequest item : request.items()) {
            reserveItem(request.orderId(), item);
        }
    }

    private void reserveItem(Long orderId, StockReservationItemRequest item) {
        if (item.qty() < 1) {
            throw new IllegalArgumentException("qty 는 1 이상이어야 한다: " + item.qty());
        }
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
            if (decreaseAvailableQty(lot.getId(), attempt) == 0) {
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

    // 락 대기 타임아웃/교착은 도메인 밖으로 raw 타입을 새어나가게 두지 않고 재시도 가능한 오류로 감싼다
    private int decreaseAvailableQty(Long stockLotId, int qty) {
        try {
            return stockLotRepository.decreaseAvailableQty(stockLotId, qty);
        } catch (PessimisticLockingFailureException e) {
            throw new StockException(StockErrorCode.RESERVATION_IN_PROGRESS, e);
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
     * 대상에서 자연히 빠져 순차 재시도해도 다시 처리되지 않는다. 조회 자체가 쓰기 락이라
     * (StockAllocationRepository.findByOrderItemIdInAndStatus 참고) 진짜 동시 중복 호출도 막힌다.
     */
    public void confirm(StockOrderItemsRequest request) {
        if (request.orderItemIds() == null || request.orderItemIds().isEmpty()) {
            return;
        }
        List<StockAllocation> allocations = findReservedAllocationsForUpdate(request.orderItemIds());
        if (allocations.isEmpty()) {
            return;
        }
        Map<Long, StockLot> lots = lockLots(allocations);
        for (StockAllocation allocation : allocations) {
            allocation.confirm();
            StockLot lot = lots.get(allocation.getStockLotId());
            stockMovementRepository.save(
                    StockMovement.confirm(lot.getId(), allocation.getQty(), lot.getAvailableQty(),
                            request.orderId()));
        }
    }

    /*
     * RESERVED 할당만 해제한다. CONFIRMED는 findByOrderItemIdInAndStatus 조건에서 아예 빠지므로
     * 잘못 해제될 수 없다. confirm()과 마찬가지로 조회 자체가 쓰기 락이라 동시 중복 호출도 막힌다.
     */
    public void release(StockOrderItemsRequest request) {
        if (request.orderItemIds() == null || request.orderItemIds().isEmpty()) {
            return;
        }
        List<StockAllocation> allocations = findReservedAllocationsForUpdate(request.orderItemIds());
        if (allocations.isEmpty()) {
            return;
        }
        Map<Long, StockLot> lots = lockLots(allocations);
        for (StockAllocation allocation : allocations) {
            allocation.release();
            StockLot lot = lots.get(allocation.getStockLotId());
            int beforeQty = lot.getAvailableQty();
            lot.restore(allocation.getQty());
            stockMovementRepository.save(
                    StockMovement.release(lot.getId(), allocation.getQty(), beforeQty, request.orderId()));
        }
    }

    // 락 대기 타임아웃/교착은 도메인 밖으로 raw 타입을 새어나가게 두지 않고 재시도 가능한 오류로 감싼다
    private List<StockAllocation> findReservedAllocationsForUpdate(List<Long> orderItemIds) {
        try {
            return stockAllocationRepository.findByOrderItemIdInAndStatus(orderItemIds, AllocationStatus.RESERVED);
        } catch (PessimisticLockingFailureException e) {
            throw new StockException(StockErrorCode.RESERVATION_IN_PROGRESS, e);
        }
    }

    /*
     * 대상 로트들에 한 번에 쓰기 락을 건다(id 오름차순, StockLotRepository.findAllByIdForUpdate 참고).
     * 할당마다 따로 잠그면 호출 순서에 따라 로트를 잠그는 순서가 달라져 교착이 날 수 있고, 조회도
     * N+1이 된다 — 한 번에 정렬해서 잠그면 두 문제가 함께 없어진다.
     * 할당이 가리키는 로트는 fk_alloc_lot이 보장하므로 항상 존재한다.
     */
    private Map<Long, StockLot> lockLots(List<StockAllocation> allocations) {
        List<Long> stockLotIds = allocations.stream()
                .map(StockAllocation::getStockLotId)
                .distinct()
                .sorted()
                .toList();
        try {
            return stockLotRepository.findAllByIdForUpdate(stockLotIds).stream()
                    .collect(Collectors.toMap(StockLot::getId, Function.identity()));
        } catch (PessimisticLockingFailureException e) {
            throw new StockException(StockErrorCode.RESERVATION_IN_PROGRESS, e);
        }
    }
}
