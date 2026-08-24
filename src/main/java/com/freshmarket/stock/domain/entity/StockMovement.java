package com.freshmarket.stock.domain.entity;

import com.freshmarket.common.entity.BaseImmutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 재고 변동 이력. 로트 단위 시간순으로 쌓이는 원장이며, available_qty를 바꾸는 연산과 같은 트랜잭션에서 함께 저장한다
@Entity
@Table(name = "stock_movement")
@AttributeOverride(name = "id", column = @Column(name = "stock_movement_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockMovement extends BaseImmutableTimeEntity {

    @Column(name = "stock_lot_id", nullable = false)
    private Long stockLotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 30)
    private MovementType movementType;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "qty_before", nullable = false)
    private int qtyBefore;

    @Column(name = "qty_after", nullable = false)
    private int qtyAfter;

    // 주문 기인 변동에서만 채운다. INBOUND는 항상 null
    @Column(name = "order_id")
    private Long orderId;

    // 관리자가 처리한 변동에서만 채운다. 관리자 인증이 아직 없어 지금은 항상 null
    @Column(name = "admin_id")
    private Long adminId;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposal_reason", length = 30)
    private DisposalReason disposalReason;

    @Column(name = "reason", length = 200)
    private String reason;

    private StockMovement(Long stockLotId, MovementType movementType, int quantity, int qtyBefore, int qtyAfter,
            Long orderId) {
        validateStockLotId(stockLotId);
        validateMovementType(movementType);
        validateQuantity(quantity);
        this.stockLotId = stockLotId;
        this.movementType = movementType;
        this.quantity = quantity;
        this.qtyBefore = qtyBefore;
        this.qtyAfter = qtyAfter;
        this.orderId = orderId;
    }

    // 신규 입고 이력을 남긴다. 가용 수량이 0에서 입고 수량만큼 늘어난 것으로 기록한다
    public static StockMovement inbound(Long stockLotId, int quantity) {
        return new StockMovement(stockLotId, MovementType.INBOUND, quantity, 0, quantity, null);
    }

    // 예약 이력을 남긴다. availableQty가 quantity만큼 줄어든 것으로 기록한다
    public static StockMovement reserve(Long stockLotId, int quantity, int qtyBefore, Long orderId) {
        return new StockMovement(stockLotId, MovementType.RESERVE, quantity, qtyBefore, qtyBefore - quantity,
                orderId);
    }

    // 확정 이력을 남긴다. availableQty는 예약 시점에 이미 빠졌으므로 앞뒤 수량이 같다
    public static StockMovement confirm(Long stockLotId, int quantity, int qtyBefore, Long orderId) {
        return new StockMovement(stockLotId, MovementType.CONFIRM, quantity, qtyBefore, qtyBefore, orderId);
    }

    // 해제 이력을 남긴다. availableQty가 quantity만큼 복원된 것으로 기록한다
    public static StockMovement release(Long stockLotId, int quantity, int qtyBefore, Long orderId) {
        return new StockMovement(stockLotId, MovementType.RELEASE, quantity, qtyBefore, qtyBefore + quantity,
                orderId);
    }

    private static void validateStockLotId(Long stockLotId) {
        if (stockLotId == null) {
            throw new IllegalArgumentException("stockLotId 는 필수다");
        }
    }

    private static void validateMovementType(MovementType movementType) {
        if (movementType == null) {
            throw new IllegalArgumentException("movementType 은 필수다");
        }
    }

    private static void validateQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 는 0보다 커야 한다: " + quantity);
        }
    }
}
