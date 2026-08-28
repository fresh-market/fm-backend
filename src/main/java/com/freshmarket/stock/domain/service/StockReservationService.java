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
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    // 옵션 하나의 FEFO 로트를 한 번에 읽어오는 최대 개수. 이보다 로트가 많으면 다음 청크를 이어서 읽는다
    private static final int LOT_CHUNK_SIZE = 50;

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
     * 재시도는 orderItemId 기준으로 먼저 일괄 조회해 걸러낸다 — 커밋된 적 없는 부분 예약은
     * 호출부 트랜잭션이 롤백되며 함께 사라지므로 남아있을 수 없다.
     *
     * productOptionId 오름차순으로 처리한다(DI-2-03) — 두 주문이 같은 옵션들을 서로 다른 순서로
     * 예약하면 조건부 UPDATE가 로트를 잠그는 순서가 갈려 교착이 날 수 있다. 같은 옵션이면 항상
     * 같은 FEFO 순서로 로트에 도달하므로, 옵션 단위로만 정렬해도 교차 잠금 시나리오가 없어진다
     * (confirm/release의 lockLots가 로트 id로 정렬하는 것과 같은 이유).
     */
    public void reserve(StockReservationRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            return;
        }
        // 요청 검증은 DB를 건드리기 전에 끝낸다 — 항목 하나라도 수량이 잘못됐으면 조회조차 하지 않는다
        for (StockReservationItemRequest item : request.items()) {
            if (item.qty() < 1) {
                throw new IllegalArgumentException("qty 는 1 이상이어야 한다: " + item.qty());
            }
        }

        List<StockReservationItemRequest> sortedItems = request.items().stream()
                .sorted(Comparator.comparing(StockReservationItemRequest::productOptionId))
                .toList();

        // 항목마다 따로 조회하던 멱등성 체크를 요청 전체 기준 IN 조회 1회로 묶는다
        Set<Long> allocatedOrderItemIds = stockAllocationRepository.findOrderItemIdsWithAllocation(
                sortedItems.stream().map(StockReservationItemRequest::orderItemId).toList());

        for (StockReservationItemRequest item : sortedItems) {
            if (allocatedOrderItemIds.contains(item.orderItemId())) {
                continue;
            }
            reserveItem(request.orderId(), item);
        }
    }

    private void reserveItem(Long orderId, StockReservationItemRequest item) {
        int remaining = item.qty();
        LocalDate lastExpiryDate = null;
        Long lastStockLotId = null;

        while (remaining > 0) {
            List<StockLot> lots = findFefoChunk(item.productOptionId(), lastExpiryDate, lastStockLotId);
            if (lots.isEmpty()) {
                break;
            }

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

            StockLot lastLot = lots.get(lots.size() - 1);
            lastExpiryDate = lastLot.getExpiryDate();
            lastStockLotId = lastLot.getId();

            if (lots.size() < LOT_CHUNK_SIZE) {
                break;
            }
        }

        if (remaining > 0) {
            throw new StockException(StockErrorCode.INSUFFICIENT_STOCK);
        }
    }

    // 커서(lastExpiryDate/lastStockLotId)가 없으면 첫 청크, 있으면 그 다음 청크를 FEFO 순서로 가져온다
    private List<StockLot> findFefoChunk(Long productOptionId, LocalDate lastExpiryDate, Long lastStockLotId) {
        Pageable limit = PageRequest.of(0, LOT_CHUNK_SIZE);
        if (lastExpiryDate == null) {
            return stockLotRepository.findFirstFefoChunk(productOptionId, LotStatus.AVAILABLE, limit);
        }
        return stockLotRepository.findNextFefoChunk(
                productOptionId, LotStatus.AVAILABLE, lastExpiryDate, lastStockLotId, limit);
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
    // 이 트랜잭션은 실패로 두고 롤백시켜, 호출부가 재시도하면 findOrderItemIdsWithAllocation으로 그 결과를 그대로 본다
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
