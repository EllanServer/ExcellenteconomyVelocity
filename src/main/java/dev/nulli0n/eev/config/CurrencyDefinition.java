package dev.nulli0n.eev.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record CurrencyDefinition(
    String id,
    String column,
    boolean decimal,
    int scale,
    BigDecimal minimumPayment,
    BigDecimal maximumBalance,
    List<String> aliases
) {
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    public CurrencyDefinition {
        id = normalizeId(id);
        if (!SQL_IDENTIFIER.matcher(column).matches()) {
            throw new IllegalArgumentException("Unsafe currency column: " + column);
        }
        if (scale < 0 || scale > 8) {
            throw new IllegalArgumentException("Currency scale must be between 0 and 8: " + id);
        }
        minimumPayment = Objects.requireNonNull(minimumPayment, "minimumPayment");
        maximumBalance = Objects.requireNonNull(maximumBalance, "maximumBalance");
        aliases = List.copyOf(aliases);
    }

    public BigDecimal normalize(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        return amount.setScale(decimal ? scale : 0, RoundingMode.DOWN);
    }

    public boolean hasMaximum() {
        return maximumBalance.signum() > 0;
    }

    public BigDecimal cap(BigDecimal balance) {
        if (!hasMaximum()) {
            return balance;
        }
        return balance.min(maximumBalance);
    }

    private static String normalizeId(String value) {
        String normalized = Objects.requireNonNull(value, "id").toLowerCase(Locale.ROOT);
        if (!SQL_IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Unsafe currency id: " + value);
        }
        return normalized;
    }
}
