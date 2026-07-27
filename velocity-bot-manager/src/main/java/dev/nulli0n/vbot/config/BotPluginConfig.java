package dev.nulli0n.vbot.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BotPluginConfig(
    ProxyEndpoint proxy,
    RuntimeConfig runtime,
    Map<String, BotDefinition> bots
) {
    public BotPluginConfig {
        bots = Map.copyOf(new LinkedHashMap<>(bots));
        if (bots.isEmpty()) {
            throw new IllegalArgumentException("At least one bot must be configured");
        }
    }

    public record ProxyEndpoint(String address, int port, String virtualHost, int virtualPort) {
    }

    public record RuntimeConfig(
        long autoStartDelayMillis,
        long spawnIntervalMillis,
        long commandIntervalMillis,
        long resourcePackStepDelayMillis,
        ResourcePackMode resourcePackMode,
        boolean autoRespawn,
        ReconnectConfig reconnect
    ) {
    }

    public record ReconnectConfig(
        long initialDelayMillis,
        long maximumDelayMillis,
        double multiplier,
        double jitter,
        int maximumAttempts
    ) {
    }

    public record BotDefinition(
        String id,
        boolean enabled,
        String username,
        String password,
        String targetServer,
        int renderDistance,
        AuthConfig auth,
        String serverSwitchCommand,
        long serverSwitchDelayMillis,
        List<String> afterLoginCommands
    ) {
        public BotDefinition {
            afterLoginCommands = List.copyOf(afterLoginCommands);
        }
    }

    public record AuthConfig(
        AuthMode mode,
        String loginCommand,
        String registerCommand,
        long loginDelayMillis,
        long fallbackRegisterDelayMillis,
        long afterAuthDelayMillis,
        List<String> loginPrompts,
        List<String> registerPrompts,
        List<String> successMessages
    ) {
        public AuthConfig {
            loginPrompts = List.copyOf(loginPrompts);
            registerPrompts = List.copyOf(registerPrompts);
            successMessages = List.copyOf(successMessages);
        }
    }

    public enum AuthMode {
        NONE,
        LOGIN,
        REGISTER,
        AUTO
    }

    public enum ResourcePackMode {
        ACCEPT_WITHOUT_DOWNLOAD,
        DECLINE
    }
}
