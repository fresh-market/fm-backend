package com.freshmarket.stock.internal.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 주문상품-로트 할당. 주문상품이 어느 로트를 얼마나 잡고 있는지 추적한다 (반품 시 원래 로트를 찾는 경로)
@Entity
@Table(name = "stock_allocation")
@AttributeOverride(name = "id", column = @Column(name = "stock_allocation_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockAllocation extends BaseMutableTimeEntity {

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "stock_lot_id", nullable = false)
    private Long stockLotId;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AllocationStatus status;

    private StockAllocation(Long orderItemId, Long stockLotId, int qty) {
        validateOrderItemId(orderItemId);
        validateStockLotId(stockLotId);
        validateQty(qty);
        this.orderItemId = orderItemId;
        this.stockLotId = stockLotId;
        this.qty = qty;
        this.status = AllocationStatus.RESERVED;
    }

    // 로트에서 수량을 예약해 할당을 새로 만든다. availableQty 차감은 호출자(조건부 UPDATE)의 책임이다
    public static StockAllocation reserve(Long orderItemId, Long stockLotId, int qty) {
        return new StockAllocation(orderItemId, stockLotId, qty);
    }

    // 예약을 확정으로 바꾼다. availableQty는 이미 예약 시점에 빠졌으므로 여기서 건드리지 않는다
    public void confirm() {
        if (status != AllocationStatus.RESERVED) {
            throw new IllegalStateException("RESERVED 상태만 확정할 수 있다: " + status);
        }
        this.status = AllocationStatus.CONFIRMED;
    }

    // 예약을 해제한다. availableQty 복원은 호출자(조건부 UPDATE)의 책임이다
    public void release() {
        if (status != AllocationStatus.RESERVED) {
            throw new IllegalStateException("RESERVED 상태만 해제할 수 있다: " + status);
        }
        this.status = AllocationStatus.RELEASED;
    }

    private static void validateOrderItemId(Long orderItemId) {
        if (orderItemId == null) {
            throw new IllegalArgumentException("orderItemId 는 필수다");
        }
    }

    private static void validateStockLotId(Long stockLotId) {
        if (stockLotId == null) {
            throw new IllegalArgumentException("stockLotId 는 필수다");
        }
    }

    private static void validateQty(int qty) {
        if (qty < 1) {
            throw new IllegalArgumentException("qty 는 1 이상이어야 한다: " + qty);
        }
    }
}
