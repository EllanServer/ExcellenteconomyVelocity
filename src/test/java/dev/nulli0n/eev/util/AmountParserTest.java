package dev.nulli0n.eev.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountParserTest {
    @Test
    void parsesCompactAmounts() {
        assertThat(AmountParser.parse("1k")).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(AmountParser.parse("2.5m")).isEqualByComparingTo(new BigDecimal("2500000"));
        assertThat(AmountParser.parse("1,234.50")).isEqualByComparingTo(new BigDecimal("1234.50"));
    }

    @Test
    void rejectsZeroNegativeAndGarbage() {
        assertThatThrownBy(() -> AmountParser.parse("0")).isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> AmountParser.parse("-1")).isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> AmountParser.parse("money")).isInstanceOf(NumberFormatException.class);
    }
}
