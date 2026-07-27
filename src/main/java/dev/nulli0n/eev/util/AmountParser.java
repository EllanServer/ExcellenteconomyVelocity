package dev.nulli0n.eev.util;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

public final class AmountParser {
    private static final Map<Character, BigDecimal> SUFFIXES = Map.of(
        'k', new BigDecimal("1000"),
        'm', new BigDecimal("1000000"),
        'b', new BigDecimal("1000000000"),
        't', new BigDecimal("1000000000000")
    );

    private AmountParser() {
    }

    public static BigDecimal parse(String input) {
        String normalized = input.trim().toLowerCase(Locale.ROOT).replace("_", "").replace(",", "");
        if (normalized.isEmpty()) {
            throw new NumberFormatException("Empty amount");
        }
        char last = normalized.charAt(normalized.length() - 1);
        BigDecimal multiplier = SUFFIXES.get(last);
        String numeric = multiplier == null ? normalized : normalized.substring(0, normalized.length() - 1);
        BigDecimal amount = new BigDecimal(numeric);
        if (multiplier != null) {
            amount = amount.multiply(multiplier);
        }
        if (amount.signum() <= 0) {
            throw new NumberFormatException("Amount must be positive");
        }
        return amount;
    }
}
