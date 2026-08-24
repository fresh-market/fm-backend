package com.freshmarket.stock.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TurnoverRateCalculatorTest {

    @Test
    void 절반이_팔리면_소진율_0_5다() {
        BigDecimal result = TurnoverRateCalculator.calculate(100, 50);

        assertThat(result).isEqualByComparingTo("0.5000");
    }

    @Test
    void 전혀_안_팔리면_소진율_0이다() {
        BigDecimal result = TurnoverRateCalculator.calculate(100, 100);

        assertThat(result).isEqualByComparingTo("0.0000");
    }

    @Test
    void 전부_팔리면_소진율_1이다() {
        BigDecimal result = TurnoverRateCalculator.calculate(100, 0);

        assertThat(result).isEqualByComparingTo("1.0000");
    }

    @Test
    void 입고_수량이_0이면_예외를_던진다() {
        assertThatThrownBy(() -> TurnoverRateCalculator.calculate(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 입고_수량이_음수면_예외를_던진다() {
        assertThatThrownBy(() -> TurnoverRateCalculator.calculate(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
