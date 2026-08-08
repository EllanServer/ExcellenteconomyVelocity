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
import java.util.ArrayList;
import java.util.HashSet;
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
    version = "1.0.2",
    description = "Velocity-only network companion for ExcellentEconomy",
    authors = {"OpenAI Codex"}
)
public final class ExcellentEconomyVelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ScheduledTask> tasks = new HashMap<>();
    private final List<CommandMeta> registeredCommands = new ArrayList<>();
    private final Set<String> registeredCommandLabels = new HashSet<>();

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

    private synchronized void registerCommands() {
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
        if (config.commands().registerPayAllAlias()) {
            register(manager, "payall", EconomyCommand.Mode.PAYALL, "eepayall");
        }
        else {
            register(manager, "eepayall", EconomyCommand.Mode.PAYALL);
        }
        if (config.commands().registerPayOfflineAlias()) {
            register(manager, "payoffline", EconomyCommand.Mode.PAYOFFLINE, "eepayoffline");
        }
        else {
            register(manager, "eepayoffline", EconomyCommand.Mode.PAYOFFLINE);
        }
        register(manager, "eesync", EconomyCommand.Mode.SYNC);
        register(manager, "eevreload", EconomyCommand.Mode.RELOAD);

        if (config.commands().registerCurrencyCommands()) {
            config.currencies().values().forEach(currency -> {
                if (registeredCommandLabels.contains(currency.id())) {
                    logger.warn("Skipped currency command /{} because that label is already registered by EEV",
                        currency.id());
                    return;
                }
                String[] aliases = currency.aliases().stream()
                    .filter(alias -> !registeredCommandLabels.contains(alias))
                    .toArray(String[]::new);
                register(manager, currency.id(), EconomyCommand.Mode.CURRENCY, currency, aliases);
            });
        }
    }

    private void register(CommandManager manager, String name, EconomyCommand.Mode mode, String... aliases) {
        register(manager, name, mode, null, aliases);
    }

    private void register(CommandManager manager, String name, EconomyCommand.Mode mode,
                          dev.nulli0n.eev.config.CurrencyDefinition currency, String... aliases) {
        CommandMeta meta = manager.metaBuilder(name).aliases(aliases).plugin(this).build();
        manager.register(meta, new EconomyCommand(this, mode, currency));
        registeredCommands.add(meta);
        registeredCommandLabels.add(name.toLowerCase(java.util.Locale.ROOT));
        for (String alias : aliases) {
            registeredCommandLabels.add(alias.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private synchronized void unregisterCommands() {
        CommandManager manager = proxy.getCommandManager();
        registeredCommands.forEach(manager::unregister);
        registeredCommands.clear();
        registeredCommandLabels.clear();
    }

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        if (config == null || ready.isCompletedExceptionally()) {
            return;
        }
        Player player = event.getPlayer();
        ready.thenRunAsync(() -> {
            heartbeat();
            deliverPendingNotifications(player);
        }, executor);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        PluginConfig currentConfig = config;
        if (currentConfig == null || ready.isCompletedExceptionally()) {
            return;
        }
        Player player = event.getPlayer();
        ready.thenRunAsync(() -> {
            if (redis != null) {
                redis.markOffline(player.getUniqueId());
            }
        }, executor);
        proxy.getScheduler().buildTask(this, () -> ready.thenRunAsync(() -> {
                try {
                    int applied = database.applyPendingGrants(player.getUniqueId(), currentConfig.currencies());
                    if (applied > 0) {
                        logger.info("Applied {} deferred payoffline grant(s) for {}", applied, player.getUsername());
                    }
                }
                catch (SQLException exception) {
                    logger.error("Could not apply deferred grants for " + player.getUsername(), exception);
                }
            }, executor))
            .delay(Duration.ofSeconds(currentConfig.payOffline().deferredDelaySeconds()))
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
                if (Set.of("GIVE", "SET", "TAKE").contains(notification.kind())) {
                    player.sendMessage(messages.get("offline-admin-operation", Map.of(
                        "amount", display(notification.amount()),
                        "currency", notification.currency(),
                        "operation", operationLabel(notification.kind())
                    )));
                }
                else {
                    player.sendMessage(messages.get("offline-notification", Map.of(
                        "amount", display(notification.amount()),
                        "currency", notification.currency(),
                        "source", notification.source()
                    )));
                }
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
            else if ("GRANT".equals(event.type())) {
                player.sendMessage(messages.get("payall-received", Map.of(
                    "amount", event.amount(),
                    "currency", event.currency(),
                    "balance", event.balance()
                )));
                markNetworkNotificationDelivered(event);
            }
            else if (Set.of("GIVE", "SET", "TAKE").contains(event.type())) {
                player.sendMessage(messages.get("admin-operation-received", Map.of(
                    "amount", event.amount(),
                    "currency", event.currency(),
                    "operation", operationLabel(event.type()),
                    "balance", event.balance()
                )));
                markNetworkNotificationDelivered(event);
            }
            else if ("CAMPAIGN_QUEUED".equals(event.type())) {
                player.sendMessage(messages.get("payoffline-queued", Map.of(
                    "amount", event.amount(),
                    "currency", event.currency()
                )));
            }
        });
    }

    private void markNetworkNotificationDelivered(NetworkEvent event) {
        if (event.transactionId() == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                database.markNotificationDelivered(event.targetUuid(), event.transactionId());
            }
            catch (SQLException exception) {
                logger.warn("Could not mark network notification delivered: {}", exception.getMessage());
            }
        }, executor);
    }

    public synchronized void reloadConfiguration() throws Exception {
        PluginConfig candidateConfig = ConfigLoader.load(dataDirectory);
        Messages candidateMessages = new Messages(candidateConfig);
        Database candidateDatabase = null;
        RedisService candidateRedis = null;
        try {
            candidateDatabase = new Database(candidateConfig);
            candidateDatabase.initialize();
            candidateRedis = new RedisService(candidateConfig, logger);
            candidateRedis.addListener(this::handleNetworkEvent);
            candidateRedis.connect();
        }
        catch (Exception exception) {
            closeCandidate(candidateRedis, candidateDatabase);
            throw exception;
        }

        PluginConfig oldConfig = config;
        Messages oldMessages = messages;
        Database oldDatabase = database;
        RedisService oldRedis = redis;
        unregisterCommands();
        config = candidateConfig;
        messages = candidateMessages;
        database = candidateDatabase;
        redis = candidateRedis;
        try {
            registerCommands();
            heartbeat();
        }
        catch (RuntimeException exception) {
            unregisterCommands();
            config = oldConfig;
            messages = oldMessages;
            database = oldDatabase;
            redis = oldRedis;
            registerCommands();
            closeCandidate(candidateRedis, candidateDatabase);
            throw exception;
        }

        closeCandidate(oldRedis, oldDatabase);
        logger.info("ExcellentEconomyVelocity reloaded as node {}", config.nodeId());
    }

    private static void closeCandidate(RedisService redis, Database database) {
        if (redis != null) {
            redis.close();
        }
        if (database != null) {
            database.close();
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        tasks.values().forEach(ScheduledTask::cancel);
        unregisterCommands();
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

    private static String operationLabel(String type) {
        return switch (type) {
            case "GIVE" -> "增加";
            case "SET" -> "设置";
            case "TAKE" -> "扣除";
            default -> type;
        };
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
