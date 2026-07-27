package dev.nulli0n.eev.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ConfigLoader {
    private ConfigLoader() {
    }

    public static PluginConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path target = dataDirectory.resolve("config.yml");
        if (Files.notExists(target)) {
            try (InputStream stream = ConfigLoader.class.getClassLoader().getResourceAsStream("config.yml")) {
                if (stream == null) {
                    throw new IOException("Bundled config.yml is missing");
                }
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Map<String, Object> root;
        try (InputStream stream = Files.newInputStream(target)) {
            root = castMap(yaml.load(stream), "root");
        }

        String nodeId = text(root, "node-id", "velocity-1");
        String defaultCurrency = text(root, "default-currency", "money").toLowerCase(Locale.ROOT);

        Map<String, Object> database = section(root, "database");
        PluginConfig.DatabaseConfig databaseConfig = new PluginConfig.DatabaseConfig(
            text(database, "jdbc-url", ""),
            text(database, "username", "root"),
            text(database, "password", ""),
            sqlIdentifier(text(database, "users-table", "excellenteconomy_users")),
            integer(database, "pool-size", 6, 1, 32),
            longValue(database, "connection-timeout-ms", 5_000L, 250L, 120_000L)
        );

        Map<String, Object> redis = section(root, "redis");
        PluginConfig.RedisConfig redisConfig = new PluginConfig.RedisConfig(
            bool(redis, "enabled", true),
            text(redis, "uri", "redis://127.0.0.1:6379/0"),
            keyPrefix(text(redis, "key-prefix", "eev")),
            bool(redis, "require-for-payments", false),
            integer(redis, "presence-ttl-seconds", 30, 5, 600)
        );

        Map<String, Object> commands = section(root, "commands");
        PluginConfig.CommandConfig commandConfig = new PluginConfig.CommandConfig(
            bool(commands, "register-pay-alias", true),
            bool(commands, "register-payments-alias", true),
            bool(commands, "register-payoffline-alias", true)
        );

        Map<String, Object> payOffline = section(root, "payoffline");
        PluginConfig.PayOfflineConfig payOfflineConfig = new PluginConfig.PayOfflineConfig(
            integer(payOffline, "batch-size", 500, 1, 5_000),
            integer(payOffline, "confirmation-seconds", 60, 10, 600),
            PluginConfig.OnlineMode.valueOf(text(payOffline, "online-mode", "SAFE_DEFER_ONLINE")
                .toUpperCase(Locale.ROOT)),
            integer(payOffline, "deferred-delay-seconds", 3, 1, 60)
        );

        Map<String, CurrencyDefinition> currencies = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : section(root, "currencies").entrySet()) {
            String id = entry.getKey().toLowerCase(Locale.ROOT);
            Map<String, Object> value = castMap(entry.getValue(), "currencies." + id);
            List<String> aliases = stringList(value.get("aliases"));
            currencies.put(id, new CurrencyDefinition(
                id,
                sqlIdentifier(text(value, "column", id)),
                bool(value, "decimal", true),
                integer(value, "scale", 2, 0, 8),
                decimal(value, "minimum-payment", BigDecimal.ONE),
                decimal(value, "maximum-balance", BigDecimal.valueOf(-1)),
                aliases
            ));
        }
        if (currencies.isEmpty()) {
            throw new IllegalArgumentException("At least one currency must be configured");
        }

        Map<String, String> messages = new LinkedHashMap<>();
        section(root, "messages").forEach((key, value) -> messages.put(key, String.valueOf(value)));

        return new PluginConfig(nodeId, defaultCurrency, databaseConfig, redisConfig, commandConfig,
            payOfflineConfig, currencies, messages);
    }

    private static Map<String, Object> section(Map<String, Object> root, String name) {
        Object value = root.get(name);
        if (value == null) {
            return Map.of();
        }
        return castMap(value, name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected YAML map at " + name);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static int integer(Map<String, Object> map, String key, int fallback, int min, int max) {
        Object value = map.get(key);
        int parsed = value == null ? fallback : Integer.parseInt(String.valueOf(value));
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    private static long longValue(Map<String, Object> map, String key, long fallback, long min, long max) {
        Object value = map.get(key);
        long parsed = value == null ? fallback : Long.parseLong(String.valueOf(value));
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    private static BigDecimal decimal(Map<String, Object> map, String key, BigDecimal fallback) {
        Object value = map.get(key);
        return value == null ? fallback : new BigDecimal(String.valueOf(value));
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        list.forEach(item -> result.add(String.valueOf(item)));
        return List.copyOf(result);
    }

    private static String sqlIdentifier(String value) {
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + value);
        }
        return value;
    }

    private static String keyPrefix(String value) {
        if (!value.matches("[A-Za-z0-9:_-]+")) {
            throw new IllegalArgumentException("Unsafe Redis key prefix: " + value);
        }
        return value;
    }
}
