package dev.nulli0n.vbot;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.nulli0n.vbot.bot.BotManager;
import dev.nulli0n.vbot.command.VBotCommand;
import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.config.ConfigLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

@Plugin(
    id = "velocitybotmanager",
    name = "VelocityBotManager",
    version = "0.1.0-SNAPSHOT",
    description = "Embedded headless Minecraft 1.21.11 clients for Velocity",
    authors = {"OpenAI Codex"}
)
public final class VelocityBotManagerPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile BotPluginConfig config;
    private volatile BotManager manager;

    @Inject
    public VelocityBotManagerPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            config = ConfigLoader.load(dataDirectory);
            manager = new BotManager(config, logger);
            registerCommand();
            manager.startEnabled();
            logger.info("VelocityBotManager initialized with {} configured bot(s)", config.bots().size());
        }
        catch (Exception exception) {
            logger.error("VelocityBotManager initialization failed", exception);
        }
    }

    private void registerCommand() {
        CommandManager commandManager = proxy.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("vbot").plugin(this).build();
        commandManager.register(meta, new VBotCommand(this));
    }

    public synchronized void reload() throws IOException {
        BotPluginConfig replacementConfig = ConfigLoader.load(dataDirectory);
        BotManager replacement = new BotManager(replacementConfig, logger);
        BotManager previous = manager;
        config = replacementConfig;
        manager = replacement;
        if (previous != null) {
            previous.close();
        }
        replacement.startEnabled();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        BotManager active = manager;
        if (active != null) {
            active.close();
        }
    }

    public BotManager manager() {
        BotManager active = manager;
        if (active == null) {
            throw new IllegalStateException("Bot manager is not initialized");
        }
        return active;
    }

    public Logger logger() {
        return logger;
    }
}
