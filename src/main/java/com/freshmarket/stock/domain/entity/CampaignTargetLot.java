package com.freshmarket.stock.domain.entity;

import com.freshmarket.common.entity.BaseImmutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 선착순 쿠폰 캠페인 대상 로트. 매일 자정 배치가 그날의 대상을 확정해 남긴다.
 * 수정되지 않는 이력성 데이터라 BaseImmutableTimeEntity(created_at 만) 를 쓴다.
 *
 * 상품이 아니라 로트 단위다. 같은 상품이라도 로트마다 소비기한이 달라, 상품
 * 단위로 두면 소비기한이 넉넉히 남은 로트까지 쿠폰이 적용된다.
 *
 * 조회는 이 표만 읽는다. 소진율과 재고는 초 단위로 변하므로, 요청 시점마다
 * 다시 계산하면 같은 기준일인데도 결과가 달라진다("동일 기준일로 재조회 시
 * 항상 동일 결과 반환" 요구사항). 그래서 배치가 확정한 시점의 값을 그대로 굳혀 둔다.
 */
@Entity
@Table(name = "campaign_target_lot")
@AttributeOverride(name = "id", column = @Column(name = "campaign_target_lot_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CampaignTargetLot extends BaseImmutableTimeEntity {

    // "상위 3건만 남긴다"는 요구사항 원문 그 자체다. chk_campaign_target_rank 와 짝을 이룬다
    private static final int MAX_TARGET_RANK = 3;

    // 대상 확정 기준일(배치 실행일). 조회는 이 값으로 그날의 스냅샷을 찾는다
    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    // 대상 로트 FK. 연관관계 매핑 대신 식별자 컬럼으로 둔다 (JPA-1-02, StockLot 과 같은 관례)
    @Column(name = "stock_lot_id", nullable = false)
    private Long stockLotId;

    // 확정 시점 소진율. (입고수량 - 잔여재고) / 입고수량, 0.0000 ~ 1.0000
    @Column(name = "turnover_rate", nullable = false)
    private BigDecimal turnoverRate;

    // 발급 가능 수량(확정 시점 로트 잔량 기준)
    @Column(name = "issuable_qty", nullable = false)
    private int issuableQty;

    // 소진율 오름차순 순위. 1이 가장 낮은(=가장 안 팔린) 로트다. 상위 3건만 저장한다
    @Column(name = "target_rank", nullable = false)
    private int targetRank;

    private CampaignTargetLot(LocalDate targetDate, Long stockLotId,
            BigDecimal turnoverRate, int issuableQty, int targetRank) {
        validateTargetDate(targetDate);
        validateStockLotId(stockLotId);
        validateTurnoverRate(turnoverRate);
        validateIssuableQty(issuableQty);
        validateTargetRank(targetRank);
        this.targetDate = targetDate;
        this.stockLotId = stockLotId;
        this.turnoverRate = turnoverRate;
        this.issuableQty = issuableQty;
        this.targetRank = targetRank;
    }

    // 자정 배치가 확정한 캠페인 대상 로트 하나를 등록한다
    public static CampaignTargetLot register(LocalDate targetDate, Long stockLotId,
            BigDecimal turnoverRate, int issuableQty, int targetRank) {
        return new CampaignTargetLot(targetDate, stockLotId, turnoverRate, issuableQty, targetRank);
    }

    private static void validateTargetDate(LocalDate targetDate) {
        if (targetDate == null) {
            throw new IllegalArgumentException("targetDate 는 필수다");
        }
    }

    private static void validateStockLotId(Long stockLotId) {
        if (stockLotId == null) {
            throw new IllegalArgumentException("stockLotId 는 필수다");
        }
    }

    private static void validateTurnoverRate(BigDecimal turnoverRate) {
        if (turnoverRate == null) {
            throw new IllegalArgumentException("turnoverRate 는 필수다");
        }
        if (turnoverRate.compareTo(BigDecimal.ZERO) < 0 || turnoverRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("turnoverRate 는 0 이상 1 이하여야 한다: " + turnoverRate);
        }
    }

    private static void validateIssuableQty(int issuableQty) {
        if (issuableQty < 0) {
            throw new IllegalArgumentException("issuableQty 는 0 이상이어야 한다: " + issuableQty);
        }
    }

    private static void validateTargetRank(int targetRank) {
        if (targetRank < 1 || targetRank > MAX_TARGET_RANK) {
            throw new IllegalArgumentException(
                    "targetRank 는 1 이상 " + MAX_TARGET_RANK + " 이하여야 한다: " + targetRank);
        }
    }
}
