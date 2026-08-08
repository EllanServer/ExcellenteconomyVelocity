package dev.nulli0n.eev.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyDefinitionTest {
    @Test
    void normalizesAndCapsAmounts() {
        CurrencyDefinition money = new CurrencyDefinition("money", "money", true, 2,
            new BigDecimal("0.01"), new BigDecimal("100"), true, true,
            "excellenteconomyvelocity.currency.money", List.of("Gold", "gold"));

        assertThat(money.normalize(new BigDecimal("12.349"))).isEqualByComparingTo("12.34");
        assertThat(money.cap(new BigDecimal("101.50"))).isEqualByComparingTo("100");
        assertThat(money.playerTrading()).isTrue();
        assertThat(money.aliases()).containsExactly("gold");
    }

    @Test
    void integerCurrencyFloorsDecimals() {
        CurrencyDefinition coins = new CurrencyDefinition("coins", "coins", false, 0,
            BigDecimal.ONE, BigDecimal.valueOf(-1), false, true,
            "excellenteconomyvelocity.currency.coins", List.of());

        assertThat(coins.normalize(new BigDecimal("4.99"))).isEqualByComparingTo("4");
    }

    @Test
    void rejectsUnsafeSqlIdentifiers() {
        assertThatThrownBy(() -> new CurrencyDefinition("money", "money`; DROP TABLE users", true, 2,
            BigDecimal.ONE, BigDecimal.valueOf(-1), true, true,
            "excellenteconomyvelocity.currency.money", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafePermissionsAndCommandAliases() {
        assertThatThrownBy(() -> new CurrencyDefinition("money", "money", true, 2,
            BigDecimal.ONE, BigDecimal.valueOf(-1), true, true, "money.pay;op", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CurrencyDefinition("money", "money", true, 2,
            BigDecimal.ONE, BigDecimal.valueOf(-1), true, true, "money.pay", List.of("bad alias")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
