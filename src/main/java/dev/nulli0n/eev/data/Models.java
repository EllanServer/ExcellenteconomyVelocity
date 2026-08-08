package dev.nulli0n.eev.data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class Models {
    private Models() {
    }

    public record PlayerProfile(UUID uuid, String name) {
    }

    public record Balance(UUID uuid, String name, BigDecimal amount) {
    }

    public enum PaymentStatus {
        SUCCESS,
        PLAYER_NOT_FOUND,
        SELF,
        INSUFFICIENT,
        PAYMENTS_DISABLED,
        TOO_SMALL,
        TARGET_LIMIT,
        FAILURE
    }

    public record PaymentResult(
        PaymentStatus status,
        UUID transactionId,
        PlayerProfile source,
        PlayerProfile target,
        BigDecimal amount,
        BigDecimal sourceBalance,
        BigDecimal targetBalance
    ) {
        public static PaymentResult failure(PaymentStatus status) {
            return new PaymentResult(status, null, null, null, null, null, null);
        }
    }

    public record PaymentsResult(PlayerProfile player, boolean enabled) {
    }

    public enum AdjustmentType {
        GIVE,
        SET,
        TAKE
    }

    public record AdjustmentResult(UUID transactionId, PlayerProfile target, AdjustmentType type,
                                   BigDecimal amount, BigDecimal balance) {
    }

    public record Notification(long id, UUID playerUuid, String kind, String currency,
                               BigDecimal amount, String source, Instant createdAt) {
    }

    public record CampaignPreview(long total, long online, long offline) {
    }

    public record CampaignResult(UUID campaignId, long paid, long deferred, long capped, long failed) {
    }

    public record GrantResult(UUID transactionId, Map<UUID, BigDecimal> balances,
                              long paid, long capped, long missing) {
        public GrantResult {
            balances = Map.copyOf(balances);
        }
    }

    public record CampaignProgress(UUID campaignId, String status, long paid, long deferred,
                                   long capped, long failed, long cursor) {
    }
}
