package dev.nulli0n.vbot.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import dev.nulli0n.vbot.VelocityBotManagerPlugin;
import dev.nulli0n.vbot.bot.BotSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class VBotCommand implements SimpleCommand {
    private static final String PERMISSION = "velocitybotmanager.admin";
    private static final List<String> ACTIONS = List.of("list", "status", "start", "stop", "reconnect", "command", "reload");

    private final VelocityBotManagerPlugin plugin;

    public VBotCommand(VelocityBotManagerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!source.hasPermission(PERMISSION)) {
            source.sendMessage(Component.text("没有权限。", NamedTextColor.RED));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            help(source);
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "list" -> list(source);
                case "status" -> status(source, args);
                case "start" -> change(source, args, "启动", plugin.manager()::start);
                case "stop" -> change(source, args, "停止", plugin.manager()::stop);
                case "reconnect" -> change(source, args, "重连", plugin.manager()::reconnect);
                case "command" -> command(source, args);
                case "reload" -> {
                    plugin.reload();
                    source.sendMessage(Component.text("配置已重载，enabled 机器人已重新排队启动。", NamedTextColor.GREEN));
                }
                default -> help(source);
            }
        }
        catch (Exception exception) {
            plugin.logger().error("/vbot command failed", exception);
            source.sendMessage(Component.text("操作失败：" + exception.getMessage(), NamedTextColor.RED));
        }
    }

    private void list(CommandSource source) {
        List<BotSnapshot> snapshots = plugin.manager().snapshots();
        source.sendMessage(Component.text("Bots (" + snapshots.size() + "):", NamedTextColor.GOLD));
        snapshots.forEach(snapshot -> source.sendMessage(Component.text(
            "- " + snapshot.id() + " / " + snapshot.username() + ": " + snapshot.state(), NamedTextColor.GRAY)));
    }

    private void status(CommandSource source, String[] args) {
        if (args.length != 2) {
            source.sendMessage(Component.text("用法：/vbot status <id>", NamedTextColor.YELLOW));
            return;
        }
        plugin.manager().find(args[1]).ifPresentOrElse(session -> {
            BotSnapshot snapshot = session.snapshot();
            source.sendMessage(Component.text(snapshot.id() + " / " + snapshot.username(), NamedTextColor.GOLD));
            source.sendMessage(Component.text("状态: " + snapshot.state()
                + " | 重连次数: " + snapshot.reconnectAttempts()
                + " | 最近断线: " + snapshot.lastDisconnectReason(), NamedTextColor.GRAY));
        }, () -> unknown(source, args[1]));
    }

    private void command(CommandSource source, String[] args) {
        if (args.length < 3) {
            source.sendMessage(Component.text("用法：/vbot command <id> <命令...>", NamedTextColor.YELLOW));
            return;
        }
        String command = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (plugin.manager().find(args[1]).isEmpty()) {
            unknown(source, args[1]);
        }
        else if (plugin.manager().command(args[1], command)) {
            source.sendMessage(Component.text("命令已发送。", NamedTextColor.GREEN));
        }
        else {
            source.sendMessage(Component.text("机器人当前不在 PLAY 状态。", NamedTextColor.RED));
        }
    }

    private void change(CommandSource source, String[] args, String verb, BotAction action) {
        if (args.length != 2) {
            source.sendMessage(Component.text("用法：/vbot " + args[0] + " <id>", NamedTextColor.YELLOW));
            return;
        }
        if (action.apply(args[1])) {
            source.sendMessage(Component.text("已请求" + verb + "机器人 " + args[1] + "。", NamedTextColor.GREEN));
        }
        else {
            unknown(source, args[1]);
        }
    }

    private void help(CommandSource source) {
        source.sendMessage(Component.text("/vbot list|status|start|stop|reconnect|command|reload", NamedTextColor.YELLOW));
    }

    private void unknown(CommandSource source, String id) {
        source.sendMessage(Component.text("未知机器人：" + id, NamedTextColor.RED));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return ACTIONS.stream().filter(action -> action.startsWith(prefix)).toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("list") && !args[0].equalsIgnoreCase("reload")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.manager().snapshots().stream().map(BotSnapshot::id)
                .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }

    private interface BotAction {
        boolean apply(String id);
    }
}
