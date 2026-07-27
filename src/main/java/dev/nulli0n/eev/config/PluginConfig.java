package dev.nulli0n.eev.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public record PluginConfig(
    String nodeId,
    String defaultCurrency,
    DatabaseConfig database,
    RedisConfig redis,
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

    public record CommandConfig(
        boolean registerPayAlias,
        boolean registerPaymentsAlias,
        boolean registerPayOfflineAlias
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
    }

    public Optional<CurrencyDefinition> currency(String id) {
        return Optional.ofNullable(currencies.get(id.toLowerCase()));
    }
}
