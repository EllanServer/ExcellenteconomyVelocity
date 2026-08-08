package dev.nulli0n.eev.redis;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.nulli0n.eev.config.PluginConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class RedisService implements AutoCloseable {
    private static final Gson GSON = new Gson();

    private final PluginConfig.RedisConfig config;
    private final String nodeId;
    private final Logger logger;
    private final List<Consumer<NetworkEvent>> listeners = new CopyOnWriteArrayList<>();

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisPubSubConnection<String, String> pubSub;

    public RedisService(PluginConfig config, Logger logger) {
        this.config = config.redis();
        this.nodeId = config.nodeId();
        this.logger = logger;
    }

    public synchronized void connect() {
        if (!config.enabled() || connection != null) {
            return;
        }
        try {
            client = RedisClient.create(config.uri());
            connection = client.connect();
            pubSub = client.connectPubSub();
            pubSub.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    onMessage(message);
                }
            });
            pubSub.sync().subscribe(channel());
            logger.info("Connected to Redis and subscribed to {}", channel());
        }
        catch (RuntimeException exception) {
            logger.warn("Redis connection failed: {}", exception.getMessage());
            close();
        }
    }

    public boolean available() {
        if (!config.enabled()) {
            return false;
        }
        try {
            return connection != null && "PONG".equalsIgnoreCase(connection.sync().ping());
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }

    public void publish(NetworkEvent event) {
        if (!available()) {
            return;
        }
        try {
            connection.sync().publish(channel(), GSON.toJson(event));
        }
        catch (RuntimeException exception) {
            logger.warn("Could not publish Redis event: {}", exception.getMessage());
        }
    }

    public void heartbeat(Set<UUID> players) {
        if (!available()) {
            return;
        }
        RedisCommands<String, String> commands = connection.sync();
        for (UUID uuid : players) {
            commands.setex(presenceKey(uuid), config.presenceTtlSeconds(), nodeId);
        }
    }

    public void markOffline(UUID uuid) {
        if (!available()) {
            return;
        }
        try {
            connection.sync().del(presenceKey(uuid));
        }
        catch (RuntimeException ignored) {
        }
    }

    public Set<UUID> onlineSnapshot() {
        Set<UUID> result = new HashSet<>();
        if (!available()) {
            return result;
        }
        try {
            String pattern = config.keyPrefix() + ":presence:*";
            for (String key : connection.sync().keys(pattern)) {
                String raw = key.substring((config.keyPrefix() + ":presence:").length());
                try {
                    result.add(UUID.fromString(raw));
                }
                catch (IllegalArgumentException ignored) {
                }
            }
        }
        catch (RuntimeException exception) {
            logger.warn("Could not read Redis presence snapshot: {}", exception.getMessage());
        }
        return result;
    }

    public void addListener(Consumer<NetworkEvent> listener) {
        listeners.add(listener);
    }

    private void onMessage(String message) {
        try {
            NetworkEvent event = GSON.fromJson(message, NetworkEvent.class);
            if (event == null || nodeId.equals(event.originNode())) {
                return;
            }
            listeners.forEach(listener -> listener.accept(event));
        }
        catch (JsonSyntaxException exception) {
            logger.warn("Ignored malformed Redis event: {}", exception.getMessage());
        }
    }

    private String channel() {
        return config.keyPrefix() + ":events";
    }

    private String presenceKey(UUID uuid) {
        return config.keyPrefix() + ":presence:" + uuid;
    }

    @Override
    public synchronized void close() {
        if (pubSub != null) {
            try {
                pubSub.close();
            }
            catch (RuntimeException ignored) {
            }
            pubSub = null;
        }
        if (connection != null) {
            try {
                connection.close();
            }
            catch (RuntimeException ignored) {
            }
            connection = null;
        }
        if (client != null) {
            try {
                client.shutdown();
            }
            catch (RuntimeException ignored) {
            }
            client = null;
        }
    }

    public record NetworkEvent(
        String type,
        String originNode,
        UUID targetUuid,
        String sourceName,
        String currency,
        String amount,
        String balance,
        UUID transactionId
    ) {
        public static NetworkEvent payment(String node, UUID target, String source, String currency,
                                           String amount, String balance, UUID transactionId) {
            return new NetworkEvent("PAYMENT", node, target, source, currency, amount, balance, transactionId);
        }

        public static NetworkEvent grant(String node, UUID target, String source, String currency,
                                         String amount, String balance, UUID transactionId) {
            return new NetworkEvent("GRANT", node, target, source, currency, amount, balance, transactionId);
        }

        public static NetworkEvent adjustment(String type, String node, UUID target, String source, String currency,
                                              String amount, String balance, UUID transactionId) {
            if (!Set.of("GIVE", "SET", "TAKE").contains(type)) {
                throw new IllegalArgumentException("Unknown adjustment type: " + type);
            }
            return new NetworkEvent(type, node, target, source, currency, amount, balance, transactionId);
        }

        public static NetworkEvent campaignQueued(String node, UUID target, String currency, String amount) {
            return new NetworkEvent("CAMPAIGN_QUEUED", node, target, "payoffline", currency, amount, null, null);
        }
    }
}
