package dev.nulli0n.eev.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.nulli0n.eev.ExcellentEconomyVelocityPlugin;
import dev.nulli0n.eev.config.CurrencyDefinition;
import dev.nulli0n.eev.data.Models.Balance;
import dev.nulli0n.eev.data.Models.CampaignPreview;
import dev.nulli0n.eev.data.Models.CampaignResult;
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
        "pay", "payments", "balance", "sync", "payoffline", "status"
    );
    private static final List<String> AMOUNT_SUGGESTIONS = List.of("1", "10", "100", "500", "1k", "1m");
    private static final List<String> STATE_SUGGESTIONS = List.of("on", "off", "toggle", "status");

    private final ExcellentEconomyVelocityPlugin plugin;
    private final Mode mode;
    private final Map<String, Confirmation> confirmations = new ConcurrentHashMap<>();

    public EconomyCommand(ExcellentEconomyVelocityPlugin plugin, Mode mode) {
        this.plugin = plugin;
        this.mode = mode;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] arguments = invocation.arguments();
        String action;
        String[] args;
        if (mode == Mode.ROOT) {
            if (arguments.length == 0) {
                help(source);
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
                    case "pay" -> pay(source, args);
                    case "payments" -> payments(source, args);
                    case "balance" -> balance(source, args);
                    case "sync" -> sync(source, args);
                    case "payoffline" -> payOffline(source, args);
                    case "status" -> status(source);
                    default -> help(source);
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

    private void pay(CommandSource source, String[] args) throws SQLException {
        requirePermission(source, "excellenteconomyvelocity.pay");
        Player sender = requirePlayer(source);
        if (args.length < 2) {
            throw failure("<yellow>用法：/pay <玩家> <金额> [货币]</yellow>");
        }
        CurrencyDefinition currency = currency(args.length >= 3 ? args[2] : plugin.config().defaultCurrency());
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

    private void payments(CommandSource source, String[] args) throws SQLException {
        requirePermission(source, "excellenteconomyvelocity.payments");
        Player player = requirePlayer(source);
        String currencyId = plugin.config().defaultCurrency();
        String state = "toggle";
        if (args.length >= 1) {
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

    private void balance(CommandSource source, String[] args) throws SQLException {
        PlayerProfile target;
        String currencyId = plugin.config().defaultCurrency();
        if (args.length == 0 || plugin.config().currency(args[0]).isPresent()) {
            Player player = requirePlayer(source);
            requirePermission(source, "excellenteconomyvelocity.balance.self");
            target = new PlayerProfile(player.getUniqueId(), player.getUsername());
            if (args.length == 1) {
                currencyId = args[0];
            }
        }
        else {
            requirePermission(source, "excellenteconomyvelocity.balance.others");
            target = plugin.database().findProfile(args[0]).orElseThrow(() ->
                new CommandFailure(plugin.messages().get("invalid-player", Map.of("player", args[0]))));
            if (args.length >= 2) {
                currencyId = args[1];
            }
        }
        CurrencyDefinition currency = currency(currencyId);
        Balance balance = plugin.database().balance(target.uuid(), currency).orElseThrow(() ->
            new CommandFailure(plugin.messages().get("invalid-player", Map.of("player", target.name()))));
        source.sendMessage(plugin.messages().get("balance", Map.of(
            "player", balance.name(),
            "currency", currency.id(),
            "balance", display(balance.amount())
        )));
    }

    private void sync(CommandSource source, String[] args) throws SQLException {
        requirePermission(source, "excellenteconomyvelocity.admin.sync");
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

    private void payOffline(CommandSource source, String[] args) throws SQLException {
        requirePermission(source, "excellenteconomyvelocity.admin.payoffline");
        if (args.length < 1) {
            throw failure("<yellow>用法：/payoffline <金额> [货币] [--confirm <确认码>]</yellow>");
        }
        CurrencyDefinition currency = currency(args.length >= 2 && !args[1].startsWith("--")
            ? args[1] : plugin.config().defaultCurrency());
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
        requirePermission(source, "excellenteconomyvelocity.admin.status");
        source.sendMessage(plugin.messages().get("status", Map.of(
            "mysql", plugin.database().ping() && plugin.database().usersTableReady() ? "OK" : "DEGRADED",
            "redis", plugin.redis() != null && plugin.redis().available() ? "OK" : "OFFLINE",
            "node", plugin.config().nodeId()
        )));
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

    private void help(CommandSource source) {
        source.sendMessage(Component.text("""
            ExcellentEconomyVelocity
            /eev pay <player> <amount> [currency]
            /eev payments [currency] [on|off|toggle|status]
            /eev balance [player] [currency]
            /eev sync <player|all>
            /eev payoffline <amount> [currency] [--confirm <token>]
            /eev status
            """));
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] raw = invocation.arguments();
        List<String> args = new ArrayList<>(Arrays.asList(raw));
        String action;
        if (mode == Mode.ROOT) {
            if (args.size() <= 1) {
                return CompletableFuture.completedFuture(filter(ROOT_COMMANDS, current(args)));
            }
            action = args.removeFirst().toLowerCase(Locale.ROOT);
        }
        else {
            action = mode.name().toLowerCase(Locale.ROOT);
        }
        String current = current(args);
        int index = Math.max(0, args.size() - 1);
        List<String> candidates = switch (action) {
            case "pay" -> index == 0 ? playerNames() : index == 1 ? AMOUNT_SUGGESTIONS : currencyIds();
            case "payments" -> index == 0 ? concat(currencyIds(), STATE_SUGGESTIONS) : STATE_SUGGESTIONS;
            case "balance" -> index == 0 ? concat(playerNames(), currencyIds()) : currencyIds();
            case "sync" -> index == 0 ? concat(List.of("all"), playerNames()) : List.of();
            case "payoffline" -> index == 0 ? AMOUNT_SUGGESTIONS : index == 1 ? currencyIds()
                : List.of("--confirm");
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

    private List<String> currencyIds() {
        return List.copyOf(plugin.config().currencies().keySet());
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
        PAYOFFLINE,
        SYNC
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
