package com.freshmarket.stock.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CampaignTargetLotTest {

    @Test
    void 정상_값이면_등록된다() {
        CampaignTargetLot lot = CampaignTargetLot.register(
                LocalDate.now(), 1L, new BigDecimal("0.1500"), 50, 1);

        assertThat(lot.getTargetDate()).isEqualTo(LocalDate.now());
        assertThat(lot.getStockLotId()).isEqualTo(1L);
        assertThat(lot.getTurnoverRate()).isEqualByComparingTo("0.1500");
        assertThat(lot.getIssuableQty()).isEqualTo(50);
        assertThat(lot.getTargetRank()).isEqualTo(1);
    }

    @Test
    void targetDate가_없으면_실패한다() {
        BigDecimal turnoverRate = new BigDecimal("0.5");

        assertThatThrownBy(() -> CampaignTargetLot.register(null, 1L, turnoverRate, 50, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 소진율이_음수면_실패한다() {
        BigDecimal turnoverRate = new BigDecimal("-0.1");
        LocalDate targetDate = LocalDate.now();

        assertThatThrownBy(() -> CampaignTargetLot.register(targetDate, 1L, turnoverRate, 50, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 소진율이_1을_넘으면_실패한다() {
        BigDecimal turnoverRate = new BigDecimal("1.1");
        LocalDate targetDate = LocalDate.now();

        assertThatThrownBy(() -> CampaignTargetLot.register(targetDate, 1L, turnoverRate, 50, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 발급가능수량이_음수면_실패한다() {
        BigDecimal turnoverRate = new BigDecimal("0.5");
        LocalDate targetDate = LocalDate.now();

        assertThatThrownBy(() -> CampaignTargetLot.register(targetDate, 1L, turnoverRate, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 순위가_1보다_작으면_실패한다() {
        BigDecimal turnoverRate = new BigDecimal("0.5");
        LocalDate targetDate = LocalDate.now();

        assertThatThrownBy(() -> CampaignTargetLot.register(targetDate, 1L, turnoverRate, 50, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
