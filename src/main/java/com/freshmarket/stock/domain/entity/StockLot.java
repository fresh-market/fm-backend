package com.freshmarket.stock.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 입고 로트. 재고는 상품이 아니라 로트 단위로 관리하고, 상품의 총재고는 그 상품 로트들의 잔량 합계다
@Entity
@Table(name = "stock_lot")
@AttributeOverride(name = "id", column = @Column(name = "stock_lot_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockLot extends BaseMutableTimeEntity {

    private static final int REQUEST_ID_MAX_LENGTH = 100;

    // 재시도 감지용 요청 식별자(클라이언트 생성). 같은 값으로 다시 입고 요청이 오면 새로 만들지 않는다 (API-5-07, AIP-155)
    @Column(name = "request_id", nullable = false, length = REQUEST_ID_MAX_LENGTH)
    private String requestId;

    // 옵션 FK. 연관관계 매핑 대신 식별자 컬럼으로 둔다 (JPA-1-02)
    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Column(name = "received_date", nullable = false)
    private LocalDate receivedDate;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "initial_qty", nullable = false)
    private int initialQty;

    // 판매 가능 수량. 예약(RESERVE)에서 빼고 해제(RELEASE)에서 되돌린다
    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private LotStatus status;

    private StockLot(String requestId, Long productOptionId, LocalDate receivedDate, LocalDate expiryDate,
            int initialQty) {
        validateRequestId(requestId);
        validateProductOptionId(productOptionId);
        validateExpiryDate(receivedDate, expiryDate);
        validateInitialQty(initialQty);
        this.requestId = requestId;
        this.productOptionId = productOptionId;
        this.receivedDate = receivedDate;
        this.expiryDate = expiryDate;
        this.initialQty = initialQty;
        this.availableQty = initialQty;
        this.status = LotStatus.AVAILABLE;
    }

    // 로트를 새로 입고한다. 가용 수량은 입고 수량과 같은 값으로 시작한다
    public static StockLot register(String requestId, Long productOptionId, LocalDate receivedDate,
            LocalDate expiryDate, int initialQty) {
        return new StockLot(requestId, productOptionId, receivedDate, expiryDate, initialQty);
    }

    /*
     * 소비기한이 지난 로트를 만료 처리한다. AVAILABLE만 전환 대상이다 — 이미 SOLD_OUT/DISPOSED/EXPIRED로
     * 바뀐 로트는 조용히 건너뛴다(호출부가 배치라 한 건 상태 불일치로 전체를 실패시키지 않는다).
     * 반환값으로 실제 전환 여부를 알려줘, 호출부가 EXPIRE 이력을 남길지와 응답에 포함할지를 결정한다.
     */
    public boolean expire() {
        if (status != LotStatus.AVAILABLE) {
            return false;
        }
        this.availableQty = 0;
        this.status = LotStatus.EXPIRED;
        return true;
    }

    // 요청 식별자가 비어있지 않고 길이 제한을 넘지 않는지 검사한다
    private static void validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 는 필수다");
        }
        if (requestId.length() > REQUEST_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "requestId 는 " + REQUEST_ID_MAX_LENGTH + "자를 넘을 수 없다: " + requestId.length());
        }
    }

    private static void validateProductOptionId(Long productOptionId) {
        if (productOptionId == null) {
            throw new IllegalArgumentException("productOptionId 는 필수다");
        }
    }

    // 소비기한이 입고일보다 이르면 안 된다. 서비스가 STOCK-001로 먼저 걸러내지만, 엔티티도 스스로 지킨다
    private static void validateExpiryDate(LocalDate receivedDate, LocalDate expiryDate) {
        if (receivedDate == null) {
            throw new IllegalArgumentException("receivedDate 는 필수다");
        }
        if (expiryDate == null) {
            throw new IllegalArgumentException("expiryDate 는 필수다");
        }
        if (expiryDate.isBefore(receivedDate)) {
            throw new IllegalArgumentException(
                    "expiryDate 는 receivedDate 이상이어야 한다: " + expiryDate + " < " + receivedDate);
        }
    }

    private static void validateInitialQty(int initialQty) {
        if (initialQty < 1) {
            throw new IllegalArgumentException("initialQty 는 1 이상이어야 한다: " + initialQty);
        }
    }
}
