package com.freshmarket.stock.internal.entity;

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

    // 소진율 오름차순 순위. 1이 가장 낮은(=가장 안 팔린) 로트다. 하위 10% 전체를 저장한다
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

    /*
     * 상한을 두지 않는다. 대상이 소진율 하위 10% 전체라 후보 수에 따라 순위가 얼마든지 커진다.
     * 한때 3으로 상한을 뒀었는데, 그 값은 요구사항이 바뀌면 같이 바뀌는 정책값이라
     * 엔티티와 CHECK 제약에 굳히면 요구사항 변경이 곧 스키마 변경이 된다.
     */
    private static void validateTargetRank(int targetRank) {
        if (targetRank < 1) {
            throw new IllegalArgumentException("targetRank 는 1 이상이어야 한다: " + targetRank);
        }
    }
}
