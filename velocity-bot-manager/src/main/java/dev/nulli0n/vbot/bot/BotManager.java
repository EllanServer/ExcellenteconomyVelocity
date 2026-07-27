package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BotManager implements AutoCloseable {
    private final BotPluginConfig config;
    private final Logger logger;
    private final ScheduledExecutorService executor;
    private final Map<String, BotSession> sessions = new ConcurrentHashMap<>();

    public BotManager(BotPluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        AtomicInteger threadId = new AtomicInteger();
        int threads = Math.max(2, Math.min(8, config.bots().size() + 1));
        this.executor = Executors.newScheduledThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "vbot-worker-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        config.bots().forEach((key, definition) -> sessions.put(key,
            new BotSession(definition, config.proxy(), config.runtime(), executor, logger)));
    }

    public void startEnabled() {
        long delay = config.runtime().autoStartDelayMillis();
        for (BotSession session : sortedSessions()) {
            if (session.definition().enabled()) {
                executor.schedule(session::start, delay, TimeUnit.MILLISECONDS);
                delay += config.runtime().spawnIntervalMillis();
            }
        }
    }

    public Optional<BotSession> find(String id) {
        return Optional.ofNullable(sessions.get(id.toLowerCase(Locale.ROOT)));
    }

    public List<BotSnapshot> snapshots() {
        return sortedSessions().stream().map(BotSession::snapshot).toList();
    }

    public boolean start(String id) {
        return find(id).map(session -> {
            session.start();
            return true;
        }).orElse(false);
    }

    public boolean stop(String id) {
        return find(id).map(session -> {
            session.stop();
            return true;
        }).orElse(false);
    }

    public boolean reconnect(String id) {
        return find(id).map(session -> {
            session.reconnectNow();
            return true;
        }).orElse(false);
    }

    public boolean command(String id, String command) {
        return find(id).map(session -> session.sendCommand(command)).orElse(false);
    }

    private List<BotSession> sortedSessions() {
        List<BotSession> result = new ArrayList<>(sessions.values());
        result.sort(Comparator.comparing(session -> session.definition().id(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    @Override
    public void close() {
        sessions.values().forEach(BotSession::stop);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Bot worker pool did not stop within five seconds");
                executor.shutdownNow();
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
