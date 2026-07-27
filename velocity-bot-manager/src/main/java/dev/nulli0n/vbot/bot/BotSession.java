package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthMode;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;
import dev.nulli0n.vbot.config.BotPluginConfig.ResourcePackMode;
import dev.nulli0n.vbot.config.BotPluginConfig.RuntimeConfig;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.clientbound.ClientboundCookieRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.serverbound.ServerboundCookieResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStartConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class BotSession {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final BotDefinition definition;
    private final ProxyEndpoint endpoint;
    private final RuntimeConfig runtime;
    private final ScheduledExecutorService executor;
    private final Logger logger;
    private final ReconnectPolicy reconnectPolicy;
    private final AtomicReference<BotState> state = new AtomicReference<>(BotState.STOPPED);
    private final AtomicBoolean manualStop = new AtomicBoolean(true);
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean loginSent = new AtomicBoolean();
    private final AtomicBoolean registerSent = new AtomicBoolean();
    private final AtomicBoolean authCompleted = new AtomicBoolean();
    private final AtomicBoolean respawnPending = new AtomicBoolean();
    private final List<Pattern> loginPrompts;
    private final List<Pattern> registerPrompts;
    private final List<Pattern> successMessages;

    private volatile ClientSession session;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile Instant connectedAt;
    private volatile String lastDisconnectReason = "never connected";
    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile float yaw;
    private volatile float pitch;

    public BotSession(BotDefinition definition, ProxyEndpoint endpoint, RuntimeConfig runtime,
                      ScheduledExecutorService executor, Logger logger) {
        this.definition = definition;
        this.endpoint = endpoint;
        this.runtime = runtime;
        this.executor = executor;
        this.logger = logger;
        this.reconnectPolicy = new ReconnectPolicy(runtime.reconnect());
        this.loginPrompts = compile(definition.auth().loginPrompts());
        this.registerPrompts = compile(definition.auth().registerPrompts());
        this.successMessages = compile(definition.auth().successMessages());
    }

    public BotDefinition definition() {
        return definition;
    }

    public BotSnapshot snapshot() {
        return new BotSnapshot(definition.id(), definition.username(), state.get(), reconnectAttempts.get(),
            connectedAt, lastDisconnectReason);
    }

    public void start() {
        manualStop.set(false);
        executor.execute(this::connectIfNeeded);
    }

    public void stop() {
        manualStop.set(true);
        generation.incrementAndGet();
        cancelReconnect();
        ClientSession active = session;
        if (active != null && active.isConnected()) {
            state.set(BotState.STOPPING);
            active.disconnect("Bot stopped by operator");
        }
        else {
            state.set(BotState.STOPPED);
        }
    }

    public void reconnectNow() {
        manualStop.set(false);
        cancelReconnect();
        generation.incrementAndGet();
        ClientSession active = session;
        if (active != null && active.isConnected()) {
            active.disconnect("Bot reconnect requested");
        }
        state.set(BotState.RECONNECT_WAIT);
        reconnectTask = executor.schedule(this::connectIfNeeded, 200, TimeUnit.MILLISECONDS);
    }

    public boolean sendCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        ClientSession active = session;
        if (normalized.isBlank() || active == null || !active.isConnected() || state.get() != BotState.PLAY) {
            return false;
        }
        active.send(new ServerboundChatCommandPacket(normalized));
        return true;
    }

    private void connectIfNeeded() {
        if (manualStop.get()) {
            state.set(BotState.STOPPED);
            return;
        }
        ClientSession active = session;
        if (active != null && active.isConnected()) {
            return;
        }

        long currentGeneration = generation.incrementAndGet();
        resetConnectionState();
        state.set(BotState.CONNECTING);
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + definition.username())
            .getBytes(StandardCharsets.UTF_8));
        MinecraftProtocol protocol = new MinecraftProtocol(new GameProfile(uuid, definition.username()), null);
        ClientSession created = ClientNetworkSessionFactory.factory()
            .setAddress(endpoint.address(), endpoint.port())
            .setProtocol(protocol)
            .setPacketHandlerExecutor(executor)
            .create();
        created.setFlag(MinecraftConstants.CLIENT_HOST, endpoint.virtualHost());
        created.setFlag(MinecraftConstants.CLIENT_PORT, endpoint.virtualPort());
        created.setFlag(MinecraftConstants.AUTOMATIC_KEEP_ALIVE_MANAGEMENT, true);
        created.setFlag(MinecraftConstants.SEND_BLANK_KNOWN_PACKS_RESPONSE, true);
        created.addListener(new Listener(currentGeneration));
        session = created;

        logger.info("Bot {} ({}) connecting to {}:{} via virtual host {}:{}",
            definition.id(), definition.username(), endpoint.address(), endpoint.port(),
            endpoint.virtualHost(), endpoint.virtualPort());
        try {
            created.connect(false);
        }
        catch (RuntimeException exception) {
            lastDisconnectReason = exception.getMessage();
            logger.warn("Bot {} could not start its connection: {}", definition.id(), exception.getMessage());
            scheduleReconnect(currentGeneration);
        }
    }

    private void resetConnectionState() {
        connectedAt = null;
        loginSent.set(false);
        registerSent.set(false);
        authCompleted.set(false);
        respawnPending.set(false);
        x = y = z = 0;
        yaw = pitch = 0;
    }

    private void onConnected(long currentGeneration) {
        if (!isCurrent(currentGeneration)) {
            return;
        }
        state.set(BotState.LOGIN);
        connectedAt = Instant.now();
        lastDisconnectReason = "connected";
    }

    private void onPacket(long currentGeneration, Session source, Packet packet) {
        if (!isCurrent(currentGeneration)) {
            return;
        }
        if (packet instanceof ClientboundLoginFinishedPacket) {
            state.set(BotState.CONFIGURATION);
            source.send(clientInformation());
        }
        else if (packet instanceof ClientboundStartConfigurationPacket) {
            state.set(BotState.CONFIGURATION);
        }
        else if (packet instanceof ClientboundFinishConfigurationPacket) {
            source.send(clientInformation());
        }
        else if (packet instanceof ClientboundLoginPacket) {
            state.set(BotState.PLAY);
            reconnectAttempts.set(0);
            logger.info("Bot {} entered PLAY", definition.id());
            scheduleAuthentication(currentGeneration);
        }
        else if (packet instanceof ClientboundCookieRequestPacket cookie) {
            source.send(new ServerboundCookieResponsePacket(cookie.getKey(), null));
        }
        else if (packet instanceof ClientboundResourcePackPushPacket resourcePack) {
            handleResourcePack(currentGeneration, source, resourcePack);
        }
        else if (packet instanceof ClientboundPlayerPositionPacket position) {
            acknowledgeTeleport(source, position);
        }
        else if (packet instanceof ClientboundSetHealthPacket health
            && health.getHealth() <= 0.0F && runtime.autoRespawn()) {
            scheduleRespawn(currentGeneration, source);
        }
        else if (packet instanceof ClientboundSystemChatPacket chat && !chat.isOverlay()) {
            handleAuthMessage(currentGeneration, PLAIN.serialize(chat.getContent()));
        }
    }

    private ServerboundClientInformationPacket clientInformation() {
        return new ServerboundClientInformationPacket(
            "zh_cn",
            definition.renderDistance(),
            ChatVisibility.FULL,
            true,
            List.of(SkinPart.values()),
            HandPreference.RIGHT_HAND,
            false,
            true,
            ParticleStatus.MINIMAL
        );
    }

    private void handleResourcePack(long currentGeneration, Session source,
                                    ClientboundResourcePackPushPacket resourcePack) {
        UUID id = resourcePack.getId();
        if (runtime.resourcePackMode() == ResourcePackMode.DECLINE) {
            source.send(new ServerboundResourcePackPacket(id, ResourcePackStatus.DECLINED));
            return;
        }
        source.send(new ServerboundResourcePackPacket(id, ResourcePackStatus.ACCEPTED));
        executor.schedule(() -> sendResourcePackStatus(currentGeneration, source, id, ResourcePackStatus.DOWNLOADED),
            runtime.resourcePackStepDelayMillis(), TimeUnit.MILLISECONDS);
        executor.schedule(() -> sendResourcePackStatus(currentGeneration, source, id, ResourcePackStatus.SUCCESSFULLY_LOADED),
            runtime.resourcePackStepDelayMillis() * 2, TimeUnit.MILLISECONDS);
    }

    private void sendResourcePackStatus(long currentGeneration, Session source, UUID id, ResourcePackStatus status) {
        if (isCurrent(currentGeneration) && source.isConnected()) {
            source.send(new ServerboundResourcePackPacket(id, status));
        }
    }

    private void acknowledgeTeleport(Session source, ClientboundPlayerPositionPacket packet) {
        Vector3d position = packet.getPosition();
        List<PositionElement> relative = packet.getRelatives();
        x = relative.contains(PositionElement.X) ? x + position.getX() : position.getX();
        y = relative.contains(PositionElement.Y) ? y + position.getY() : position.getY();
        z = relative.contains(PositionElement.Z) ? z + position.getZ() : position.getZ();
        yaw = relative.contains(PositionElement.Y_ROT) ? yaw + packet.getYRot() : packet.getYRot();
        pitch = relative.contains(PositionElement.X_ROT) ? pitch + packet.getXRot() : packet.getXRot();
        source.send(new ServerboundAcceptTeleportationPacket(packet.getId()));
        source.send(new ServerboundMovePlayerPosRotPacket(false, false, x, y, z, yaw, pitch));
    }

    private void scheduleRespawn(long currentGeneration, Session source) {
        if (!respawnPending.compareAndSet(false, true)) {
            return;
        }
        executor.schedule(() -> {
            if (isCurrent(currentGeneration) && source.isConnected() && state.get() == BotState.PLAY) {
                source.send(new ServerboundClientCommandPacket(ClientCommand.RESPAWN));
            }
            respawnPending.set(false);
        }, 1, TimeUnit.SECONDS);
    }

    private void scheduleAuthentication(long currentGeneration) {
        AuthMode mode = definition.auth().mode();
        if (mode == AuthMode.NONE) {
            completeAuthentication(currentGeneration);
            return;
        }
        executor.schedule(() -> {
            if (!isPlayable(currentGeneration) || authCompleted.get()) {
                return;
            }
            if (mode == AuthMode.REGISTER) {
                sendRegister();
                scheduleAuthCompletion(currentGeneration);
            }
            else {
                sendLogin();
                if (mode == AuthMode.LOGIN) {
                    scheduleAuthCompletion(currentGeneration);
                }
                else {
                    executor.schedule(() -> {
                        if (isPlayable(currentGeneration) && !authCompleted.get()) {
                            sendRegister();
                            scheduleAuthCompletion(currentGeneration);
                        }
                    }, definition.auth().fallbackRegisterDelayMillis(), TimeUnit.MILLISECONDS);
                }
            }
        }, definition.auth().loginDelayMillis(), TimeUnit.MILLISECONDS);
    }

    private void handleAuthMessage(long currentGeneration, String message) {
        if (authCompleted.get() || definition.auth().mode() == AuthMode.NONE) {
            return;
        }
        if (matches(successMessages, message)) {
            completeAuthentication(currentGeneration);
        }
        else if (matches(registerPrompts, message)) {
            sendRegister();
            scheduleAuthCompletion(currentGeneration);
        }
        else if (matches(loginPrompts, message)) {
            sendLogin();
            scheduleAuthCompletion(currentGeneration);
        }
    }

    private void sendLogin() {
        if (loginSent.compareAndSet(false, true)) {
            sendCommand(CommandTemplate.render(definition.auth().loginCommand(), definition));
        }
    }

    private void sendRegister() {
        if (registerSent.compareAndSet(false, true)) {
            sendCommand(CommandTemplate.render(definition.auth().registerCommand(), definition));
        }
    }

    private void scheduleAuthCompletion(long currentGeneration) {
        executor.schedule(() -> completeAuthentication(currentGeneration),
            definition.auth().afterAuthDelayMillis(), TimeUnit.MILLISECONDS);
    }

    private void completeAuthentication(long currentGeneration) {
        if (!isPlayable(currentGeneration) || !authCompleted.compareAndSet(false, true)) {
            return;
        }
        long delay = 0;
        if (!definition.targetServer().isBlank() && !definition.serverSwitchCommand().isBlank()) {
            scheduleCommand(currentGeneration, CommandTemplate.render(definition.serverSwitchCommand(), definition), delay);
            delay += definition.serverSwitchDelayMillis();
        }
        for (String command : definition.afterLoginCommands()) {
            scheduleCommand(currentGeneration, CommandTemplate.render(command, definition), delay);
            delay += runtime.commandIntervalMillis();
        }
    }

    private void scheduleCommand(long currentGeneration, String command, long delay) {
        executor.schedule(() -> sendWhenPlayable(currentGeneration, command, 0), delay, TimeUnit.MILLISECONDS);
    }

    private void sendWhenPlayable(long currentGeneration, String command, int attempt) {
        if (!isCurrent(currentGeneration) || manualStop.get()) {
            return;
        }
        if (sendCommand(command)) {
            return;
        }
        if (attempt < 20) {
            executor.schedule(() -> sendWhenPlayable(currentGeneration, command, attempt + 1),
                250, TimeUnit.MILLISECONDS);
        }
        else {
            logger.warn("Bot {} could not execute a queued command because it never returned to PLAY", definition.id());
        }
    }

    private void onDisconnected(long currentGeneration, DisconnectedEvent event) {
        if (!isCurrent(currentGeneration)) {
            return;
        }
        connectedAt = null;
        lastDisconnectReason = PLAIN.serialize(event.getReason());
        if (event.getCause() != null) {
            logger.warn("Bot {} disconnected: {}", definition.id(), lastDisconnectReason, event.getCause());
        }
        else {
            logger.info("Bot {} disconnected: {}", definition.id(), lastDisconnectReason);
        }
        session = null;
        if (manualStop.get()) {
            state.set(BotState.STOPPED);
        }
        else {
            scheduleReconnect(currentGeneration);
        }
    }

    private synchronized void scheduleReconnect(long currentGeneration) {
        if (!isCurrent(currentGeneration) || manualStop.get()) {
            return;
        }
        if (reconnectTask != null && !reconnectTask.isDone()) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        if (!reconnectPolicy.allows(attempt)) {
            state.set(BotState.FAILED);
            logger.error("Bot {} exhausted {} reconnect attempts", definition.id(), attempt - 1);
            return;
        }
        long delay = reconnectPolicy.delayMillis(attempt, ThreadLocalRandom.current().nextDouble());
        state.set(BotState.RECONNECT_WAIT);
        logger.info("Bot {} reconnect attempt {} in {} ms", definition.id(), attempt, delay);
        reconnectTask = executor.schedule(this::connectIfNeeded, delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelReconnect() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    private boolean isPlayable(long currentGeneration) {
        ClientSession active = session;
        return isCurrent(currentGeneration) && active != null && active.isConnected() && state.get() == BotState.PLAY;
    }

    private boolean isCurrent(long currentGeneration) {
        return generation.get() == currentGeneration;
    }

    private static List<Pattern> compile(List<String> expressions) {
        return expressions.stream().map(Pattern::compile).toList();
    }

    private static boolean matches(List<Pattern> patterns, String value) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(value).find());
    }

    private final class Listener extends SessionAdapter {
        private final long currentGeneration;

        private Listener(long currentGeneration) {
            this.currentGeneration = currentGeneration;
        }

        @Override
        public void connected(ConnectedEvent event) {
            onConnected(currentGeneration);
        }

        @Override
        public void packetReceived(Session session, Packet packet) {
            onPacket(currentGeneration, session, packet);
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            onDisconnected(currentGeneration, event);
        }
    }
}
