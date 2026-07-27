package dev.nulli0n.eev.redis;

import dev.nulli0n.eev.config.CurrencyDefinition;
import dev.nulli0n.eev.config.PluginConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "EEV_TEST_REDIS_URI", matches = ".+")
class RedisIntegrationTest {
    @Test
    void sharesPresenceAndPaymentEventsAcrossNodes() throws Exception {
        String redisUri = System.getenv("EEV_TEST_REDIS_URI");
        String prefix = "eev-junit-" + UUID.randomUUID();
        RedisService first = new RedisService(config("node-a", redisUri, prefix),
            LoggerFactory.getLogger("redis-test-a"));
        RedisService second = new RedisService(config("node-b", redisUri, prefix),
            LoggerFactory.getLogger("redis-test-b"));
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<RedisService.NetworkEvent> received = new AtomicReference<>();
            second.addListener(event -> {
                received.set(event);
                latch.countDown();
            });
            first.connect();
            second.connect();
            assertThat(first.available()).isTrue();
            assertThat(second.available()).isTrue();

            UUID player = UUID.randomUUID();
            first.heartbeat(Set.of(player));
            assertThat(second.onlineSnapshot()).contains(player);

            first.publish(RedisService.NetworkEvent.payment("node-a", player, "Alice", "money",
                "5", "25", UUID.randomUUID()));
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get().type()).isEqualTo("PAYMENT");
            assertThat(received.get().targetUuid()).isEqualTo(player);
        }
        finally {
            first.close();
            second.close();
        }
    }

    private static PluginConfig config(String node, String uri, String prefix) {
        CurrencyDefinition money = new CurrencyDefinition("money", "money", true, 2,
            new BigDecimal("0.01"), BigDecimal.valueOf(-1), List.of());
        return new PluginConfig(
            node,
            "money",
            new PluginConfig.DatabaseConfig("jdbc:mysql://unused", "", "", "excellenteconomy_users", 2, 1000),
            new PluginConfig.RedisConfig(true, uri, prefix, false, 30),
            new PluginConfig.CommandConfig(false, false, false),
            new PluginConfig.PayOfflineConfig(100, 60, PluginConfig.OnlineMode.SAFE_DEFER_ONLINE, 1),
            Map.of("money", money),
            Map.of()
        );
    }
}
