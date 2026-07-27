package dev.nulli0n.eev;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.nulli0n.eev.command.EconomyCommand;
import dev.nulli0n.eev.config.ConfigLoader;
import dev.nulli0n.eev.config.PluginConfig;
import dev.nulli0n.eev.data.Database;
import dev.nulli0n.eev.data.Models.Notification;
import dev.nulli0n.eev.message.Messages;
import dev.nulli0n.eev.redis.RedisService;
import dev.nulli0n.eev.redis.RedisService.NetworkEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Plugin(
    id = "excellenteconomyvelocity",
    name = "ExcellentEconomyVelocity",
    version = BuildVersion.VALUE,
    description = "Velocity-only network companion for ExcellentEconomy",
    authors = {"OpenAI Codex"}
)
public final class ExcellentEconomyVelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ScheduledTask> tasks = new HashMap<>();

    private volatile PluginConfig config;
    private volatile Messages messages;
    private volatile Database database;
    private volatile RedisService redis;
    private volatile CompletableFuture<Void> ready = new CompletableFuture<>();

    @Inject
    public ExcellentEconomyVelocityPlugin(ProxyServer proxy, Logger logger,
                                          @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            this.config = ConfigLoader.load(dataDirectory);
            this.messages = new Messages(config);
        }
        catch (Exception exception) {
            logger.error("Could not load ExcellentEconomyVelocity configuration", exception);
            ready.completeExceptionally(exception);
            return;
        }

        registerCommands();
        ready = CompletableFuture.runAsync(() -> {
            try {
                this.database = new Database(config);
                this.database.initialize();
                this.redis = new RedisService(config, logger);
                this.redis.addListener(this::handleNetworkEvent);
                this.redis.connect();
                logger.info("ExcellentEconomyVelocity initialized as node {}", config.nodeId());
            }
            catch (Exception exception) {
                logger.error("ExcellentEconomyVelocity initialization failed", exception);
                closeServices();
                throw new RuntimeException(exception);
            }
        }, executor);

        ScheduledTask heartbeat = proxy.getScheduler().buildTask(this, () ->
                ready.thenRunAsync(this::heartbeat, executor))
            .repeat(Duration.ofSeconds(10))
            .schedule();
        tasks.put("heartbeat", heartbeat);
    }

    private void registerCommands() {
        CommandManager manager = proxy.getCommandManager();
        register(manager, "eev", EconomyCommand.Mode.ROOT, "excellenteconomyvelocity");
        if (config.commands().registerPayAlias()) {
            register(manager, "pay", EconomyCommand.Mode.PAY, "eepay");
        }
        else {
            register(manager, "eepay", EconomyCommand.Mode.PAY);
        }
        if (config.commands().registerPaymentsAlias()) {
            register(manager, "payments", EconomyCommand.Mode.PAYMENTS, "eepayments");
        }
        else {
            register(manager, "eepayments", EconomyCommand.Mode.PAYMENTS);
        }
        if (config.commands().registerPayOfflineAlias()) {
            register(manager, "payoffline", EconomyCommand.Mode.PAYOFFLINE, "eepayoffline");
        }
        else {
            register(manager, "eepayoffline", EconomyCommand.Mode.PAYOFFLINE);
        }
        register(manager, "eesync", EconomyCommand.Mode.SYNC);
    }

    private void register(CommandManager manager, String name, EconomyCommand.Mode mode, String... aliases) {
        CommandMeta meta = manager.metaBuilder(name).aliases(aliases).plugin(this).build();
        manager.register(meta, new EconomyCommand(this, mode));
    }

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        ready.thenRunAsync(() -> {
            heartbeat();
            deliverPendingNotifications(player);
        }, executor);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        ready.thenRunAsync(() -> {
            if (redis != null) {
                redis.markOffline(player.getUniqueId());
            }
        }, executor);
        proxy.getScheduler().buildTask(this, () -> ready.thenRunAsync(() -> {
                try {
                    int applied = database.applyPendingGrants(player.getUniqueId(), config.currencies());
                    if (applied > 0) {
                        logger.info("Applied {} deferred payoffline grant(s) for {}", applied, player.getUsername());
                    }
                }
                catch (SQLException exception) {
                    logger.error("Could not apply deferred grants for " + player.getUsername(), exception);
                }
            }, executor))
            .delay(Duration.ofSeconds(config.payOffline().deferredDelaySeconds()))
            .schedule();
    }

    private void heartbeat() {
        if (redis == null) {
            return;
        }
        Set<UUID> players = proxy.getAllPlayers().stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet());
        redis.heartbeat(players);
    }

    private void deliverPendingNotifications(Player player) {
        try {
            List<Notification> pending = database.pendingNotifications(player.getUniqueId(), 50);
            if (pending.isEmpty()) {
                return;
            }
            for (Notification notification : pending) {
                player.sendMessage(messages.get("offline-notification", Map.of(
                    "amount", display(notification.amount()),
                    "currency", notification.currency(),
                    "source", notification.source()
                )));
            }
            database.markNotificationsDelivered(pending.stream().map(Notification::id).toList());
        }
        catch (SQLException exception) {
            logger.error("Could not deliver pending economy notifications to " + player.getUsername(), exception);
        }
    }

    private void handleNetworkEvent(NetworkEvent event) {
        proxy.getPlayer(event.targetUuid()).ifPresent(player -> {
            if ("PAYMENT".equals(event.type())) {
                player.sendMessage(messages.get("pay-received", Map.of(
                    "player", event.sourceName(),
                    "amount", event.amount(),
                    "currency", event.currency(),
                    "balance", event.balance()
                )));
                if (event.transactionId() != null) {
                    CompletableFuture.runAsync(() -> {
                        try {
                            database.markNotificationDelivered(event.targetUuid(), event.transactionId());
                        }
                        catch (SQLException exception) {
                            logger.warn("Could not mark network notification delivered: {}", exception.getMessage());
                        }
                    }, executor);
                }
            }
            else if ("CAMPAIGN_QUEUED".equals(event.type())) {
                player.sendMessage(messages.get("payoffline-queued", Map.of(
                    "amount", event.amount(),
                    "currency", event.currency()
                )));
            }
        });
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        tasks.values().forEach(ScheduledTask::cancel);
        closeServices();
        executor.shutdown();
    }

    private void closeServices() {
        if (redis != null) {
            redis.close();
            redis = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
    }

    private static String display(java.math.BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public Logger logger() {
        return logger;
    }

    public PluginConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public Database database() {
        return database;
    }

    public RedisService redis() {
        return redis;
    }

    public ExecutorService executor() {
        return executor;
    }

    public CompletableFuture<Void> ready() {
        return ready;
    }
}
