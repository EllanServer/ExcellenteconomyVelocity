package dev.nulli0n.eev.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.nulli0n.eev.ExcellentEconomyVelocityPlugin;
import dev.nulli0n.eev.config.CurrencyDefinition;
import dev.nulli0n.eev.data.Models.Balance;
import dev.nulli0n.eev.data.Models.AdjustmentResult;
import dev.nulli0n.eev.data.Models.AdjustmentType;
import dev.nulli0n.eev.data.Models.CampaignPreview;
import dev.nulli0n.eev.data.Models.CampaignResult;
import dev.nulli0n.eev.data.Models.GrantResult;
import dev.nulli0n.eev.data.Models.PaymentResult;
import dev.nulli0n.eev.data.Models.PaymentStatus;
import dev.nulli0n.eev.data.Models.PaymentsResult;
import dev.nulli0n.eev.data.Models.PlayerProfile;
import dev.nulli0n.eev.redis.RedisService.NetworkEvent;
import dev.nulli0n.eev.util.AmountParser;
import net.kyori.adventure.text.Component;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class EconomyCommand implements SimpleCommand {
    private static final List<String> ROOT_COMMANDS = List.of(
        "pay", "payments", "balance", "give", "giveall", "set", "take", "sync", "payall", "payoffline",
        "status", "reload"
    );
    private static final List<String> CURRENCY_COMMANDS = List.of(
        "balance", "payments", "pay", "give", "giveall", "set", "take", "payall", "payoffline"
    );
    private static final List<String> AMOUNT_SUGGESTIONS = List.of("1", "10", "100", "500", "1k", "1m");
    private static final List<String> STATE_SUGGESTIONS = List.of("on", "off", "toggle", "status");

    private final ExcellentEconomyVelocityPlugin plugin;
    private final Mode mode;
    private final CurrencyDefinition fixedCurrency;
    private final Map<String, Confirmation> confirmations = new ConcurrentHashMap<>();

    public EconomyCommand(ExcellentEconomyVelocityPlugin plugin, Mode mode) {
        this(plugin, mode, null);
    }

    public EconomyCommand(ExcellentEconomyVelocityPlugin plugin, Mode mode, CurrencyDefinition fixedCurrency) {
        this.plugin = plugin;
        this.mode = mode;
        this.fixedCurrency = fixedCurrency;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] arguments = invocation.arguments();
        String action;
        String[] args;
        if (mode == Mode.ROOT || mode == Mode.CURRENCY) {
            if (arguments.length == 0) {
                if (mode == Mode.CURRENCY) {
                    currencyHelp(source);
                }
                else {
                    help(source);
                }
                return;
            }
            action = arguments[0].toLowerCase(Locale.ROOT);
            args = Arrays.copyOfRange(arguments, 1, arguments.length);
        }
        else {
            action = mode.name().toLowerCase(Locale.ROOT);
            args = arguments;
        }

        plugin.ready().thenRunAsync(() -> {
            try {
                switch (action) {
                    case "pay" -> pay(source, args, fixedCurrency);
                    case "payments" -> payments(source, args, fixedCurrency);
                    case "balance" -> balance(source, args, fixedCurrency);
                    case "give" -> adminAdjust(source, args, fixedCurrency, AdjustmentType.GIVE);
                    case "giveall" -> payAll(source, args, fixedCurrency,
                        plugin.config().permissions().giveAll());
                    case "set" -> adminAdjust(source, args, fixedCurrency, AdjustmentType.SET);
                    case "take" -> adminAdjust(source, args, fixedCurrency, AdjustmentType.TAKE);
                    case "sync" -> sync(source, args);
                    case "payall" -> payAll(source, args, fixedCurrency,
                        plugin.config().permissions().payAll());
                    case "payoffline" -> payOffline(source, args, fixedCurrency);
                    case "status" -> status(source);
                    case "reload" -> reload(source);
                    default -> {
                        if (mode == Mode.CURRENCY) {
                            currencyHelp(source);
                        }
                        else {
                            help(source);
                        }
                    }
                }
            }
            catch (CommandFailure failure) {
                source.sendMessage(failure.message);
            }
            catch (Exception exception) {
                plugin.logger().error("Economy command failed", exception);
                source.sendMessage(plugin.messages().get("database-error"));
            }
        }, plugin.executor()).exceptionally(exception -> {
            plugin.logger().error("Economy command could not run because plugin initialization failed", exception);
            source.sendMessage(plugin.messages().get("database-error"));
            return null;
        });
    }

    private void pay(CommandSource source, String[] args, CurrencyDefinition selectedCurrency) throws SQLException {
        requirePermission(source, plugin.config().permissions().pay());
        Player sender = requirePlayer(source);
        if (args.length < 2) {
            throw failure("<yellow>用法：/pay <玩家> <金额> [货币]</yellow>");
        }
        CurrencyDefinition currency = selectedCurrency != null ? selectedCurrency
            : currency(args.length >= 3 ? args[2] : plugin.config().defaultCurrency());
        requireCurrencyPermission(source, currency);
        if (!currency.playerTrading()) {
            throw new CommandFailure(plugin.messages().get("currency-trade-disabled", Map.of(
                "currency", currency.id()
            )));
        }
        BigDecimal amount = amount(args[1], currency);
        if (plugin.config().redis().requireForPayments()
            && (plugin.redis() == null || !plugin.redis().available())) {
            throw new CommandFailure(plugin.messages().get("redis-required"));
        }

        PlayerProfile target = plugin.proxy().getPlayer(args[0])
            .map(player -> new PlayerProfile(player.getUniqueId(), player.getUsername()))
            .orElseGet(() -> {
                try {
                    return plugin.database().findProfile(args[0]).orElse(null);
                }
                catch (SQLException exception) {
                    throw new RuntimeException(exception);
                }
            });
        if (target == null) {
            throw new CommandFailure(plugin.messages().get("invalid-player", Map.of("player", args[0])));
        }

        PaymentResult result = plugin.database().pay(sender.getUniqueId(), target.uuid(), currency, amount);
        if (result.status() != PaymentStatus.SUCCESS) {
            sendPaymentFailure(source, result, target, currency);
            return;
        }

        sender.sendMessage(plugin.messages().get("pay-success", Map.of(
            "player", result.target().name(),
            "amount", display(result.amount()),
            "currency", currency.id(),
            "balance", display(result.sourceBalance()),
            "transaction", result.transactionId().toString().substring(0, 8)
        )));

        Optional<Player> localTarget = plugin.proxy().getPlayer(result.target().uuid());
        if (localTarget.isPresent()) {
            localTarget.get().sendMessage(plugin.messages().get("pay-received", Map.of(
                "player", result.source().name(),
                "amount", display(result.amount()),
                "currency", currency.id(),
                "balance", display(result.targetBalance())
            )));
            plugin.database().markNotificationDelivered(result.target().uuid(), result.transactionId());
        }
        else if (plugin.redis() != null) {
            plugin.redis().publish(NetworkEvent.payment(plugin.config().nodeId(), result.target().uuid(),
                result.source().name(), currency.id(), display(result.amount()), display(result.targetBalance()),
                result.transactionId()));
        }
    }

    private void sendPaymentFailure(CommandSource source, PaymentResult result, PlayerProfile target,
                                    CurrencyDefinition currency) {
        String key = switch (result.status()) {
            case SELF -> "pay-self";
            case INSUFFICIENT -> "pay-insufficient";
            case PAYMENTS_DISABLED -> "pay-disabled";
            case TOO_SMALL -> "pay-too-small";
            case TARGET_LIMIT -> "pay-target-limit";
            default -> "database-error";
        };
        Map<String, Object> replacements = Map.of(
            "player", target.name(),
            "balance", result.sourceBalance() == null ? "?" : display(result.sourceBalance()),
            "amount", display(currency.minimumPayment())
        );
        source.sendMessage(plugin.messages().get(key, replacements));
    }

    private void payments(CommandSource source, String[] args, CurrencyDefinition selectedCurrency)
        throws SQLException {
        requirePermission(source, plugin.config().permissions().payments());
        Player player = requirePlayer(source);
        String currencyId = selectedCurrency == null ? plugin.config().defaultCurrency() : selectedCurrency.id();
        String state = "toggle";
        if (selectedCurrency != null && args.length >= 1) {
            state = args[0];
        }
        else if (args.length >= 1) {
            if (plugin.config().currency(args[0]).isPresent()) {
                currencyId = args[0];
                if (args.length >= 2) {
                    state = args[1];
                }
            }
            else {
                state = args[0];
            }
        }
        CurrencyDefinition currency = currency(currencyId);
        requireCurrencyPermission(source, currency);
        Optional<PaymentsResult> result;
        boolean statusOnly = state.equalsIgnoreCase("status");
        if (statusOnly) {
            result = plugin.database().paymentsStatus(player.getUniqueId(), currency);
        }
        else {
            Optional<Boolean> requested = switch (state.toLowerCase(Locale.ROOT)) {
                case "on", "true", "enable", "enabled" -> Optional.of(true);
                case "off", "false", "disable", "disabled" -> Optional.of(false);
                case "toggle" -> Optional.empty();
                default -> throw failure("<red>状态必须是 on、off、toggle 或 status。</red>");
            };
            result = plugin.database().payments(player.getUniqueId(), currency, requested);
        }
        PaymentsResult value = result.orElseThrow(() ->
            new CommandFailure(plugin.messages().get("invalid-player", Map.of("player", player.getUsername()))));
        source.sendMessage(plugin.messages().get(statusOnly ? "payments-state" : "payments-changed", Map.of(
            "currency", currency.id(),
            "state", value.enabled() ? "ON" : "OFF"
        )));
    }

    private void balance(CommandSource source, String[] args, CurrencyDefinition selectedCurrency)
        throws SQLException {
        requirePermission(source, plugin.config().permissions().balance());
        PlayerProfile target;
        String currencyId = selectedCurrency == null ? plugin.config().defaultCurrency() : selectedCurrency.id();
        if (args.length == 0 || (selectedCurrency == null && plugin.config().currency(args[0]).isPresent())) {
            Player player = requirePlayer(source);
            target = new PlayerProfile(player.getUniqueId(), player.getUsername());
            if (selectedCurrency == null && args.length == 1) {
                currencyId = args[0];
            }
        }
        else {
            requirePermission(source, plugin.config().permissions().balanceOthers());
            target = plugin.database().findProfile(args[0]).orElseThrow(() ->
                new CommandFailure(plugin.messages().get("invalid-player", Map.of("player", args[0]))));
            if (selectedCurrency == null && args.length >= 2) {
                currencyId = args[1];
            }
        }
        CurrencyDefinition currency = currency(currencyId);
        requireCurrencyPermission(source, currency);
        Balance balance = plugin.database().balance(target.uuid(), currency).orElseThrow(() ->
            new CommandFailure(plugin.messages().get("invalid-player", Map.of("player", target.name()))));
        source.sendMessage(plugin.messages().get("balance", Map.of(
            "player", balance.name(),
            "currency", currency.id(),
            "balance", display(balance.amount())
        )));
    }

    private void adminAdjust(CommandSource source, String[] args, CurrencyDefinition selectedCurrency,
                             AdjustmentType type) throws SQLException {
        String permission = switch (type) {
            case GIVE -> plugin.config().permissions().give();
            case SET -> plugin.config().permissions().set();
            case TAKE -> plugin.config().permissions().take();
        };
        requirePermission(source, permission);
        if (args.length < 2) {
            throw failure("<yellow>用法：/货币 " + type.name().toLowerCase(Locale.ROOT)
                + " <玩家> <金额></yellow>");
        }
        CurrencyDefinition currency = selectedCurrency != null ? selectedCurrency
            : currency(args.length >= 3 ? args[2] : plugin.config().defaultCurrency());
        requireCurrencyPermission(source, currency);
        BigDecimal amount = type == AdjustmentType.SET
            ? nonNegativeAmount(args[1], currency)
            : amount(args[1], currency);
        PlayerProfile target = plugin.proxy().getPlayer(args[0])
            .map(player -> new PlayerProfile(player.getUniqueId(), player.getUsername()))
            .orElseGet(() -> {
                try {
                    return plugin.database().findProfile(args[0]).orElse(null);
                }
                catch (SQLException exception) {
                    throw new RuntimeException(exception);
                }
            });
        if (target == null) {
            throw new CommandFailure(plugin.messages().get("invalid-player", Map.of("player", args[0])));
        }

        UUID actorUuid = source instanceof Player player ? player.getUniqueId() : null;
        AdjustmentResult result = plugin.database().adjustBalance(actorUuid, displayName(source), target.uuid(),
            currency, amount, type).orElseThrow(() ->
                new CommandFailure(plugin.messages().get("invalid-player", Map.of("player", args[0]))));
        String operation = operationLabel(type);
        source.sendMessage(plugin.messages().get("admin-operation-success", Map.of(
            "player", result.target().name(),
            "currency", currency.id(),
            "operation", operation,
            "amount", display(result.amount()),
            "balance", display(result.balance()),
            "transaction", result.transactionId().toString().substring(0, 8)
        )));

        Optional<Player> local = plugin.proxy().getPlayer(target.uuid());
        if (local.isPresent()) {
            local.get().sendMessage(plugin.messages().get("admin-operation-received", Map.of(
                "currency", currency.id(),
                "operation", operation,
                "amount", display(result.amount()),
                "balance", display(result.balance())
            )));
            plugin.database().markNotificationDelivered(target.uuid(), result.transactionId());
        }
        else if (plugin.redis() != null) {
            plugin.redis().publish(NetworkEvent.adjustment(type.name(), plugin.config().nodeId(), target.uuid(),
                displayName(source), currency.id(), display(result.amount()), display(result.balance()),
                result.transactionId()));
        }
    }

    private void sync(CommandSource source, String[] args) throws SQLException {
        requirePermission(source, plugin.config().permissions().sync());
        if (args.length < 1) {
            throw failure("<yellow>用法：/eesync <玩家|all></yellow>");
        }
        Optional<UUID> uuid;
        if (args[0].equalsIgnoreCase("all")) {
            uuid = Optional.empty();
        }
        else {
            PlayerProfile profile = plugin.database().findProfile(args[0]).orElseThrow(() ->
                new CommandFailure(plugin.messages().get("invalid-player", Map.of("player", args[0]))));
            uuid = Optional.of(profile.uuid());
        }
        int count = plugin.database().triggerSync(uuid, plugin.config().nodeId());
        if (count < 0) {
            source.sendMessage(plugin.messages().get("sync-unavailable"));
            return;
        }
        source.sendMessage(plugin.messages().get("sync-done", Map.of("count", count)));
    }

    private void payAll(CommandSource source, String[] args, CurrencyDefinition selectedCurrency,
                        String permission)
        throws SQLException {
        requirePermission(source, permission);
        if (args.length < 1) {
            throw failure("<yellow>用法：/payall <金额> [货币]</yellow>");
        }
        CurrencyDefinition currency = selectedCurrency != null ? selectedCurrency
            : currency(args.length >= 2 ? args[1] : plugin.config().defaultCurrency());
        requireCurrencyPermission(source, currency);
        BigDecimal amount = amount(args[0], currency);
        Set<UUID> online = onlineSnapshot();
        if (online.isEmpty()) {
            source.sendMessage(plugin.messages().get("payall-empty"));
            return;
        }

        UUID transactionId = UUID.randomUUID();
        UUID actorUuid = source instanceof Player player ? player.getUniqueId() : null;
        GrantResult result = plugin.database().grantPlayers(transactionId, actorUuid, displayName(source), online,
            currency, amount);
        for (Map.Entry<UUID, BigDecimal> entry : result.balances().entrySet()) {
            Optional<Player> local = plugin.proxy().getPlayer(entry.getKey());
            if (local.isPresent()) {
                local.get().sendMessage(plugin.messages().get("payall-received", Map.of(
                    "amount", display(amount),
                    "currency", currency.id(),
                    "balance", display(entry.getValue())
                )));
                plugin.database().markNotificationDelivered(entry.getKey(), transactionId);
            }
            else if (plugin.redis() != null) {
                plugin.redis().publish(NetworkEvent.grant(plugin.config().nodeId(), entry.getKey(),
                    displayName(source), currency.id(), display(amount), display(entry.getValue()), transactionId));
            }
        }
        source.sendMessage(plugin.messages().get("payall-complete", Map.of(
            "paid", result.paid(),
            "capped", result.capped(),
            "missing", result.missing(),
            "transaction", transactionId.toString().substring(0, 8)
        )));
    }

    private void payOffline(CommandSource source, String[] args, CurrencyDefinition selectedCurrency)
        throws SQLException {
        requirePermission(source, plugin.config().permissions().payOffline());
        if (args.length < 1) {
            throw failure("<yellow>用法：/payoffline <金额> [货币] [--confirm <确认码>]</yellow>");
        }
        CurrencyDefinition currency = selectedCurrency != null ? selectedCurrency
            : currency(args.length >= 2 && !args[1].startsWith("--")
                ? args[1] : plugin.config().defaultCurrency());
        requireCurrencyPermission(source, currency);
        BigDecimal amount = amount(args[0], currency);
        int confirmIndex = indexOf(args, "--confirm");
        String actor = actorKey(source);
        if (confirmIndex < 0 || confirmIndex + 1 >= args.length) {
            Set<UUID> online = onlineSnapshot();
            CampaignPreview preview = plugin.database().previewCampaign(online);
            String token = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
            confirmations.put(actor, new Confirmation(token, currency.id(), amount,
                Instant.now().plusSeconds(plugin.config().payOffline().confirmationSeconds())));
            source.sendMessage(plugin.messages().get("payoffline-preview", Map.of(
                "total", preview.total(),
                "online", plugin.config().payOffline().onlineMode()
                    == dev.nulli0n.eev.config.PluginConfig.OnlineMode.SAFE_DEFER_ONLINE ? preview.online() : 0,
                "amount", display(amount),
                "currency", currency.id(),
                "token", token
            )));
            return;
        }

        String token = args[confirmIndex + 1].toUpperCase(Locale.ROOT);
        Confirmation confirmation = confirmations.remove(actor);
        if (confirmation == null || confirmation.expiresAt().isBefore(Instant.now())
            || !confirmation.token().equals(token)
            || !confirmation.currency().equals(currency.id())
            || confirmation.amount().compareTo(amount) != 0) {
            throw failure("<red>确认码无效或已过期，请重新执行命令获取预览。</red>");
        }

        UUID campaignHint = UUID.randomUUID();
        source.sendMessage(plugin.messages().get("payoffline-started", Map.of("campaign", campaignHint)));
        Set<UUID> online = onlineSnapshot();
        CampaignResult result = plugin.database().runCampaign(campaignHint, displayName(source), currency, amount, online,
            plugin.config().payOffline().onlineMode(), plugin.config().payOffline().batchSize());
        notifyDeferredPlayers(online, currency, amount);
        source.sendMessage(plugin.messages().get("payoffline-complete", Map.of(
            "paid", result.paid(),
            "deferred", result.deferred(),
            "capped", result.capped(),
            "failed", result.failed(),
            "campaign", result.campaignId()
        )));
    }

    private void notifyDeferredPlayers(Set<UUID> online, CurrencyDefinition currency, BigDecimal amount) {
        if (plugin.config().payOffline().onlineMode()
            != dev.nulli0n.eev.config.PluginConfig.OnlineMode.SAFE_DEFER_ONLINE) {
            return;
        }
        for (UUID uuid : online) {
            Optional<Player> local = plugin.proxy().getPlayer(uuid);
            if (local.isPresent()) {
                local.get().sendMessage(plugin.messages().get("payoffline-queued", Map.of(
                    "amount", display(amount), "currency", currency.id()
                )));
            }
            else if (plugin.redis() != null) {
                plugin.redis().publish(NetworkEvent.campaignQueued(plugin.config().nodeId(), uuid, currency.id(),
                    display(amount)));
            }
        }
    }

    private void status(CommandSource source) {
        requirePermission(source, plugin.config().permissions().status());
        source.sendMessage(plugin.messages().get("status", Map.of(
            "mysql", plugin.database().ping() && plugin.database().usersTableReady() ? "OK" : "DEGRADED",
            "redis", plugin.redis() != null && plugin.redis().available() ? "OK" : "OFFLINE",
            "node", plugin.config().nodeId()
        )));
    }

    private void reload(CommandSource source) {
        requirePermission(source, plugin.config().permissions().reload());
        try {
            plugin.reloadConfiguration();
            source.sendMessage(plugin.messages().get("reload-success"));
        }
        catch (Exception exception) {
            plugin.logger().error("Could not reload ExcellentEconomyVelocity", exception);
            source.sendMessage(plugin.messages().get("reload-failed"));
        }
    }

    private Set<UUID> onlineSnapshot() {
        Set<UUID> result = new LinkedHashSet<>();
        plugin.proxy().getAllPlayers().forEach(player -> result.add(player.getUniqueId()));
        if (plugin.redis() != null) {
            result.addAll(plugin.redis().onlineSnapshot());
        }
        return result;
    }

    private CurrencyDefinition currency(String id) {
        return plugin.config().currency(id).orElseThrow(() ->
            new CommandFailure(plugin.messages().get("invalid-currency", Map.of("currency", id))));
    }

    private BigDecimal amount(String raw, CurrencyDefinition currency) {
        try {
            BigDecimal parsed = currency.normalize(AmountParser.parse(raw));
            if (parsed.signum() <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        }
        catch (NumberFormatException exception) {
            throw new CommandFailure(plugin.messages().get("invalid-amount"));
        }
    }

    private BigDecimal nonNegativeAmount(String raw, CurrencyDefinition currency) {
        try {
            BigDecimal parsed = currency.normalize(AmountParser.parse(raw));
            if (parsed.signum() < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        }
        catch (NumberFormatException exception) {
            throw new CommandFailure(plugin.messages().get("invalid-amount"));
        }
    }

    private Player requirePlayer(CommandSource source) {
        if (!(source instanceof Player player)) {
            throw new CommandFailure(plugin.messages().get("player-only"));
        }
        return player;
    }

    private void requirePermission(CommandSource source, String permission) {
        if (!source.hasPermission(permission)) {
            throw new CommandFailure(plugin.messages().get("no-permission"));
        }
    }

    private void requireCurrencyPermission(CommandSource source, CurrencyDefinition currency) {
        if (currency.permissionRequired() && !source.hasPermission(currency.permission())) {
            throw new CommandFailure(plugin.messages().get("currency-pay-no-permission", Map.of(
                "currency", currency.id()
            )));
        }
    }

    private static String operationLabel(AdjustmentType type) {
        return switch (type) {
            case GIVE -> "增加";
            case SET -> "设置";
            case TAKE -> "扣除";
        };
    }

    private void help(CommandSource source) {
        source.sendMessage(Component.text("""
            ExcellentEconomyVelocity
            /eev pay <player> <amount> [currency]
            /eev payments [currency] [on|off|toggle|status]
            /eev balance [player] [currency]
            /eev give|set|take <player> <amount> [currency]
            /eev giveall <amount> [currency]
            /eev sync <player|all>
            /eev payall <amount> [currency]
            /eev payoffline <amount> [currency] [--confirm <token>]
            /eev status
            /eev reload
            """));
    }

    private void currencyHelp(CommandSource source) {
        source.sendMessage(Component.text("""
            /%s pay <player> <amount>
            /%s payments [on|off|toggle|status]
            /%s balance [player]
            /%s give|set|take <player> <amount>
            /%s giveall <amount>
            /%s payall <amount>
            /%s payoffline <amount> [--confirm <token>]
            """.formatted(fixedCurrency.id(), fixedCurrency.id(), fixedCurrency.id(), fixedCurrency.id(),
            fixedCurrency.id(), fixedCurrency.id(), fixedCurrency.id())));
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] raw = invocation.arguments();
        List<String> args = new ArrayList<>(Arrays.asList(raw));
        String action;
        if (mode == Mode.ROOT || mode == Mode.CURRENCY) {
            if (args.size() <= 1) {
                List<String> actions = (mode == Mode.CURRENCY ? CURRENCY_COMMANDS : ROOT_COMMANDS).stream()
                    .filter(candidate -> canUseAction(invocation.source(), candidate))
                    .toList();
                return CompletableFuture.completedFuture(filter(
                    actions, current(args)));
            }
            action = args.removeFirst().toLowerCase(Locale.ROOT);
        }
        else {
            action = mode.name().toLowerCase(Locale.ROOT);
        }
        String current = current(args);
        int index = Math.max(0, args.size() - 1);
        List<String> candidates = switch (action) {
            case "pay" -> index == 0 ? playerNames() : index == 1 ? AMOUNT_SUGGESTIONS
                : fixedCurrency == null ? tradableCurrencyIds(invocation.source()) : List.of();
            case "payments" -> fixedCurrency != null ? STATE_SUGGESTIONS
                : index == 0 ? concat(currencyIds(invocation.source()), STATE_SUGGESTIONS) : STATE_SUGGESTIONS;
            case "balance" -> fixedCurrency != null ? index == 0 ? playerNames() : List.of()
                : index == 0 ? concat(playerNames(), currencyIds(invocation.source()))
                    : currencyIds(invocation.source());
            case "give", "set", "take" -> index == 0 ? playerNames() : index == 1 ? AMOUNT_SUGGESTIONS
                : fixedCurrency == null ? currencyIds(invocation.source()) : List.of();
            case "sync" -> index == 0 ? concat(List.of("all"), playerNames()) : List.of();
            case "giveall", "payall" -> index == 0 ? AMOUNT_SUGGESTIONS
                : fixedCurrency == null && index == 1 ? currencyIds(invocation.source()) : List.of();
            case "payoffline" -> index == 0 ? AMOUNT_SUGGESTIONS
                : fixedCurrency == null && index == 1 ? currencyIds(invocation.source()) : List.of("--confirm");
            default -> List.of();
        };
        return CompletableFuture.completedFuture(filter(candidates, current));
    }

    private List<String> playerNames() {
        Set<String> names = new LinkedHashSet<>();
        plugin.proxy().getAllPlayers().forEach(player -> names.add(player.getUsername()));
        if (plugin.database() != null) {
            names.addAll(plugin.database().cachedProfileNames());
        }
        return List.copyOf(names);
    }

    private List<String> currencyIds(CommandSource source) {
        return plugin.config().currencies().values().stream()
            .filter(currency -> hasCurrencyPermission(source, currency))
            .map(CurrencyDefinition::id)
            .toList();
    }

    private List<String> tradableCurrencyIds(CommandSource source) {
        return plugin.config().currencies().values().stream()
            .filter(CurrencyDefinition::playerTrading)
            .filter(currency -> hasCurrencyPermission(source, currency))
            .map(CurrencyDefinition::id)
            .toList();
    }

    private boolean canUseAction(CommandSource source, String action) {
        if (fixedCurrency != null && !hasCurrencyPermission(source, fixedCurrency)) {
            return false;
        }
        String permission = switch (action) {
            case "pay" -> plugin.config().permissions().pay();
            case "payments" -> plugin.config().permissions().payments();
            case "balance" -> plugin.config().permissions().balance();
            case "give" -> plugin.config().permissions().give();
            case "giveall" -> plugin.config().permissions().giveAll();
            case "set" -> plugin.config().permissions().set();
            case "take" -> plugin.config().permissions().take();
            case "payall" -> plugin.config().permissions().payAll();
            case "payoffline" -> plugin.config().permissions().payOffline();
            case "sync" -> plugin.config().permissions().sync();
            case "status" -> plugin.config().permissions().status();
            case "reload" -> plugin.config().permissions().reload();
            default -> null;
        };
        return permission == null || source.hasPermission(permission);
    }

    private static boolean hasCurrencyPermission(CommandSource source, CurrencyDefinition currency) {
        return !currency.permissionRequired() || source.hasPermission(currency.permission());
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> values = new ArrayList<>(first);
        values.addAll(second);
        return values;
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).limit(100).toList();
    }

    private static String current(List<String> args) {
        return args.isEmpty() ? "" : args.getLast();
    }

    private static int indexOf(String[] values, String needle) {
        for (int index = 0; index < values.length; index++) {
            if (values[index].equalsIgnoreCase(needle)) {
                return index;
            }
        }
        return -1;
    }

    private static String actorKey(CommandSource source) {
        return source instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    private static String displayName(CommandSource source) {
        return source instanceof Player player ? player.getUsername() : "CONSOLE";
    }

    private static String display(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static CommandFailure failure(String miniMessageLike) {
        return new CommandFailure(Component.text(miniMessageLike.replaceAll("<[^>]+>", "")));
    }

    public enum Mode {
        ROOT,
        PAY,
        PAYMENTS,
        PAYALL,
        PAYOFFLINE,
        SYNC,
        RELOAD,
        CURRENCY
    }

    private record Confirmation(String token, String currency, BigDecimal amount, Instant expiresAt) {
    }

    private static final class CommandFailure extends RuntimeException {
        private final Component message;

        private CommandFailure(Component message) {
            this.message = message;
        }
    }
}
