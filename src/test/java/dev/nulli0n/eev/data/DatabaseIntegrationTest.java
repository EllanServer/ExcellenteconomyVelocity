package dev.nulli0n.eev.data;

import dev.nulli0n.eev.config.CurrencyDefinition;
import dev.nulli0n.eev.config.PluginConfig;
import dev.nulli0n.eev.data.Models.CampaignResult;
import dev.nulli0n.eev.data.Models.PaymentStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "EEV_TEST_DB_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseIntegrationTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static Database database;
    private static CurrencyDefinition money;

    @BeforeAll
    static void initialize() throws Exception {
        String url = System.getenv("EEV_TEST_DB_URL");
        String user = System.getenv().getOrDefault("EEV_TEST_DB_USER", "root");
        String password = System.getenv().getOrDefault("EEV_TEST_DB_PASSWORD", "eev_test_password");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS eev_pending_grants");
            statement.execute("DROP TABLE IF EXISTS eev_notifications");
            statement.execute("DROP TABLE IF EXISTS eev_transactions");
            statement.execute("DROP TABLE IF EXISTS eev_campaigns");
            statement.execute("DROP TABLE IF EXISTS excellenteconomy_users");
            statement.execute("""
                CREATE TABLE excellenteconomy_users (
                  id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  uuid CHAR(36) NOT NULL UNIQUE,
                  name VARCHAR(32) NOT NULL,
                  last_seen BIGINT NOT NULL DEFAULT 0,
                  settings JSON NULL,
                  hiddenFromTops BOOLEAN NOT NULL DEFAULT FALSE,
                  money DOUBLE NOT NULL DEFAULT 0,
                  coins DOUBLE NOT NULL DEFAULT 0,
                  last_modified TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                    ON UPDATE CURRENT_TIMESTAMP(6),
                  last_updated_by VARCHAR(36) NOT NULL DEFAULT 'test'
                ) ENGINE=InnoDB
                """);
            statement.execute("""
                INSERT INTO excellenteconomy_users (uuid, name, settings, money, coins) VALUES
                ('00000000-0000-0000-0000-000000000001', 'Alice', '{}', 100, 10),
                ('00000000-0000-0000-0000-000000000002', 'Bob', '{}', 20, 20),
                ('00000000-0000-0000-0000-000000000003', 'Carol', '{}', 5, 30)
                """);
        }

        money = new CurrencyDefinition("money", "money", true, 2, new BigDecimal("0.01"),
            new BigDecimal("1000"), List.of("money"));
        PluginConfig config = config(url, user, password);
        database = new Database(config);
        database.initialize();
    }

    @AfterAll
    static void close() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @Order(1)
    void paymentIsAtomicAndRespectsPaymentsPreference() throws Exception {
        var paid = database.pay(ALICE, BOB, money, new BigDecimal("25.25"));
        assertThat(paid.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(database.balance(ALICE, money).orElseThrow().amount()).isEqualByComparingTo("74.75");
        assertThat(database.balance(BOB, money).orElseThrow().amount()).isEqualByComparingTo("45.25");

        var disabled = database.payments(BOB, money, Optional.of(false)).orElseThrow();
        assertThat(disabled.enabled()).isFalse();
        var rejected = database.pay(ALICE, BOB, money, BigDecimal.ONE);
        assertThat(rejected.status()).isEqualTo(PaymentStatus.PAYMENTS_DISABLED);
        assertThat(database.balance(ALICE, money).orElseThrow().amount()).isEqualByComparingTo("74.75");
    }

    @Test
    @Order(2)
    void payOfflineDefersOnlineUserThenAppliesExactlyOnce() throws Exception {
        UUID campaignId = UUID.randomUUID();
        CampaignResult result = database.runCampaign(campaignId, "JUnit", money, BigDecimal.TEN,
            Set.of(ALICE), PluginConfig.OnlineMode.SAFE_DEFER_ONLINE, 2);

        assertThat(result.paid()).isEqualTo(2);
        assertThat(result.deferred()).isEqualTo(1);
        BigDecimal before = database.balance(ALICE, money).orElseThrow().amount();

        assertThat(database.applyPendingGrants(ALICE, Map.of("money", money))).isEqualTo(1);
        assertThat(database.applyPendingGrants(ALICE, Map.of("money", money))).isZero();
        assertThat(database.balance(ALICE, money).orElseThrow().amount())
            .isEqualByComparingTo(before.add(BigDecimal.TEN));
    }

    @Test
    @Order(3)
    void syncTriggerAndProfileCacheWork() throws Exception {
        assertThat(database.cachedProfileNames()).contains("Alice", "Bob", "Carol");
        assertThat(database.triggerSync(Optional.of(CAROL), "velocity-test")).isEqualTo(1);
        assertThat(database.triggerSync(Optional.empty(), "velocity-test")).isEqualTo(3);
    }

    private static PluginConfig config(String url, String user, String password) {
        return new PluginConfig(
            "integration-test",
            "money",
            new PluginConfig.DatabaseConfig(url, user, password, "excellenteconomy_users", 4, 5_000),
            new PluginConfig.RedisConfig(false, "redis://127.0.0.1:36379/0", "eev-test", false, 30),
            new PluginConfig.CommandConfig(false, false, false),
            new PluginConfig.PayOfflineConfig(2, 60, PluginConfig.OnlineMode.SAFE_DEFER_ONLINE, 1),
            Map.of("money", money),
            Map.of()
        );
    }
}
