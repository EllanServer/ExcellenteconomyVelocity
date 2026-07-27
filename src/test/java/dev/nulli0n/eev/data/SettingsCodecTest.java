package dev.nulli0n.eev.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsCodecTest {
    @Test
    void missingSettingsDefaultToEnabledLikeExcellentEconomy() {
        assertThat(SettingsCodec.paymentsEnabled(null, "money")).isTrue();
        assertThat(SettingsCodec.paymentsEnabled("{}", "money")).isTrue();
        assertThat(SettingsCodec.paymentsEnabled("{not-json", "money")).isTrue();
    }

    @Test
    void updatesOnlyRequestedCurrency() {
        String original = """
            {"coins":{"paymentsEnabled":false},"money":{"paymentsEnabled":true}}
            """;
        String updated = SettingsCodec.setPaymentsEnabled(original, "money", false);

        assertThat(SettingsCodec.paymentsEnabled(updated, "money")).isFalse();
        assertThat(SettingsCodec.paymentsEnabled(updated, "coins")).isFalse();
    }
}
