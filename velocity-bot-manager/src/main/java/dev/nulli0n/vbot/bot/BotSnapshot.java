package dev.nulli0n.vbot.bot;

import java.time.Instant;

public record BotSnapshot(
    String id,
    String username,
    BotState state,
    int reconnectAttempts,
    Instant connectedAt,
    String lastDisconnectReason
) {
}
