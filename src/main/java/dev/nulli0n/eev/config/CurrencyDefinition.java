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
    boolean playerTrading,
    boolean permissionRequired,
    String permission,
    List<String> aliases
) {
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern COMMAND_ALIAS = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern PERMISSION = Pattern.compile("[a-z0-9._-]+");

    public CurrencyDefinition {
        String normalizedId = normalizeId(id);
        id = normalizedId;
        if (!SQL_IDENTIFIER.matcher(column).matches()) {
            throw new IllegalArgumentException("Unsafe currency column: " + column);
        }
        if (scale < 0 || scale > 8) {
            throw new IllegalArgumentException("Currency scale must be between 0 and 8: " + id);
        }
        minimumPayment = Objects.requireNonNull(minimumPayment, "minimumPayment");
        maximumBalance = Objects.requireNonNull(maximumBalance, "maximumBalance");
        permission = Objects.requireNonNull(permission, "permission").toLowerCase(Locale.ROOT);
        if (!PERMISSION.matcher(permission).matches()) {
            throw new IllegalArgumentException("Unsafe currency permission: " + permission);
        }
        aliases = Objects.requireNonNull(aliases, "aliases").stream()
            .map(CurrencyDefinition::normalizeAlias)
            .distinct()
            .filter(alias -> !alias.equals(normalizedId))
            .toList();
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

    private static String normalizeAlias(String value) {
        String normalized = Objects.requireNonNull(value, "alias").toLowerCase(Locale.ROOT);
        if (!COMMAND_ALIAS.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Unsafe currency command alias: " + value);
        }
        return normalized;
    }
}
