package dev.nulli0n.eev.config;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record PluginConfig(
    String nodeId,
    String defaultCurrency,
    DatabaseConfig database,
    RedisConfig redis,
    PermissionConfig permissions,
    CommandConfig commands,
    PayOfflineConfig payOffline,
    Map<String, CurrencyDefinition> currencies,
    Map<String, String> messages
) {
    public record DatabaseConfig(
        String jdbcUrl,
        String username,
        String password,
        String usersTable,
        int poolSize,
        long connectionTimeoutMs
    ) {
    }

    public record RedisConfig(
        boolean enabled,
        String uri,
        String keyPrefix,
        boolean requireForPayments,
        int presenceTtlSeconds
    ) {
    }

    public record PermissionConfig(
        String pay,
        String payments,
        String balance,
        String balanceOthers,
        String give,
        String giveAll,
        String set,
        String take,
        String payAll,
        String payOffline,
        String sync,
        String status,
        String reload
    ) {
    }

    public record CommandConfig(
        boolean registerPayAlias,
        boolean registerPaymentsAlias,
        boolean registerPayAllAlias,
        boolean registerPayOfflineAlias,
        boolean registerCurrencyCommands
    ) {
    }

    public record PayOfflineConfig(
        int batchSize,
        int confirmationSeconds,
        OnlineMode onlineMode,
        int deferredDelaySeconds
    ) {
    }

    public enum OnlineMode {
        SAFE_DEFER_ONLINE,
        IMMEDIATE_ALL
    }

    public PluginConfig {
        currencies = Map.copyOf(new LinkedHashMap<>(currencies));
        messages = Map.copyOf(messages);
        if (!currencies.containsKey(defaultCurrency)) {
            throw new IllegalArgumentException("default-currency is not configured: " + defaultCurrency);
        }
        Set<String> commandLabels = new HashSet<>();
        for (CurrencyDefinition currency : currencies.values()) {
            if (!commandLabels.add(currency.id())) {
                throw new IllegalArgumentException("Duplicate currency command label: " + currency.id());
            }
            for (String alias : currency.aliases()) {
                if (!commandLabels.add(alias)) {
                    throw new IllegalArgumentException("Duplicate currency command label: " + alias);
                }
            }
        }
    }

    public Optional<CurrencyDefinition> currency(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        CurrencyDefinition direct = currencies.get(normalized);
        if (direct != null) {
            return Optional.of(direct);
        }
        return currencies.values().stream()
            .filter(currency -> currency.aliases().contains(normalized))
            .findFirst();
    }
}
