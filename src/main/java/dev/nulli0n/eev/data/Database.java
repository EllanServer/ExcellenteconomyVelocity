package dev.nulli0n.eev.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nulli0n.eev.config.CurrencyDefinition;
import dev.nulli0n.eev.config.PluginConfig;
import dev.nulli0n.eev.data.Models.Balance;
import dev.nulli0n.eev.data.Models.CampaignPreview;
import dev.nulli0n.eev.data.Models.CampaignProgress;
import dev.nulli0n.eev.data.Models.CampaignResult;
import dev.nulli0n.eev.data.Models.Notification;
import dev.nulli0n.eev.data.Models.PaymentResult;
import dev.nulli0n.eev.data.Models.PaymentStatus;
import dev.nulli0n.eev.data.Models.PaymentsResult;
import dev.nulli0n.eev.data.Models.PlayerProfile;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Database implements AutoCloseable {
    private final PluginConfig config;
    private final HikariDataSource dataSource;
    private final Set<String> profileNames = ConcurrentHashMap.newKeySet();

    public Database(PluginConfig config) {
        this.config = config;
        PluginConfig.DatabaseConfig database = config.database();
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("ExcellentEconomyVelocity");
        hikari.setJdbcUrl(database.jdbcUrl());
        hikari.setUsername(database.username());
        hikari.setPassword(database.password());
        hikari.setMaximumPoolSize(database.poolSize());
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(database.connectionTimeoutMs());
        hikari.setAutoCommit(true);
        hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        this.dataSource = new HikariDataSource(hikari);
    }

    public void initialize() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS eev_transactions (
                  transaction_id CHAR(36) PRIMARY KEY,
                  transaction_type VARCHAR(32) NOT NULL,
                  actor_uuid CHAR(36) NULL,
                  source_uuid CHAR(36) NULL,
                  target_uuid CHAR(36) NULL,
                  currency VARCHAR(64) NOT NULL,
                  amount DECIMAL(38,8) NOT NULL,
                  status VARCHAR(24) NOT NULL,
                  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                  INDEX idx_eev_tx_source (source_uuid, created_at),
                  INDEX idx_eev_tx_target (target_uuid, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS eev_notifications (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  player_uuid CHAR(36) NOT NULL,
                  transaction_id CHAR(36) NULL,
                  kind VARCHAR(32) NOT NULL,
                  currency VARCHAR(64) NOT NULL,
                  amount DECIMAL(38,8) NOT NULL,
                  source_name VARCHAR(64) NOT NULL,
                  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                  delivered_at TIMESTAMP(6) NULL,
                  UNIQUE KEY uq_eev_notification (player_uuid, transaction_id, kind),
                  INDEX idx_eev_notification_pending (player_uuid, delivered_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS eev_campaigns (
                  campaign_id CHAR(36) PRIMARY KEY,
                  actor VARCHAR(64) NOT NULL,
                  currency VARCHAR(64) NOT NULL,
                  amount DECIMAL(38,8) NOT NULL,
                  status VARCHAR(24) NOT NULL,
                  cursor_id BIGINT NOT NULL DEFAULT 0,
                  paid_count BIGINT NOT NULL DEFAULT 0,
                  deferred_count BIGINT NOT NULL DEFAULT 0,
                  capped_count BIGINT NOT NULL DEFAULT 0,
                  failed_count BIGINT NOT NULL DEFAULT 0,
                  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                  completed_at TIMESTAMP(6) NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS eev_pending_grants (
                  campaign_id CHAR(36) NOT NULL,
                  player_uuid CHAR(36) NOT NULL,
                  currency VARCHAR(64) NOT NULL,
                  amount DECIMAL(38,8) NOT NULL,
                  state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                  applied_at TIMESTAMP(6) NULL,
                  PRIMARY KEY (campaign_id, player_uuid),
                  INDEX idx_eev_pending_player (player_uuid, state)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        }
        refreshProfileNames();
    }

    public boolean ping() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet ignored = statement.executeQuery()) {
            return true;
        }
        catch (SQLException ignored) {
            return false;
        }
    }

    public boolean usersTableReady() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            try (ResultSet result = metadata.getTables(catalog, null, config.database().usersTable(),
                new String[]{"TABLE"})) {
                return result.next();
            }
        }
        catch (SQLException ignored) {
            return false;
        }
    }

    public void refreshProfileNames() throws SQLException {
        if (!usersTableReady()) {
            return;
        }
        Set<String> loaded = ConcurrentHashMap.newKeySet();
        String sql = "SELECT name FROM `" + config.database().usersTable() + "`";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String name = result.getString(1);
                if (name != null && !name.isBlank()) {
                    loaded.add(name);
                }
            }
        }
        profileNames.clear();
        profileNames.addAll(loaded);
    }

    public Set<String> cachedProfileNames() {
        return Set.copyOf(profileNames);
    }

    public Optional<PlayerProfile> findProfile(String name) throws SQLException {
        String sql = "SELECT uuid, name FROM `" + config.database().usersTable()
            + "` WHERE LOWER(name) = LOWER(?) LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                PlayerProfile profile = new PlayerProfile(readUuid(result, "uuid"), result.getString("name"));
                profileNames.add(profile.name());
                return Optional.of(profile);
            }
        }
    }

    public Optional<PlayerProfile> findProfile(UUID uuid) throws SQLException {
        String sql = "SELECT uuid, name FROM `" + config.database().usersTable() + "` WHERE uuid = ? LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PlayerProfile(readUuid(result, "uuid"), result.getString("name")));
            }
        }
    }

    public Optional<Balance> balance(UUID uuid, CurrencyDefinition currency) throws SQLException {
        String sql = "SELECT uuid, name, `" + currency.column() + "` AS balance FROM `"
            + config.database().usersTable() + "` WHERE uuid = ? LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Balance(readUuid(result, "uuid"), result.getString("name"),
                    currency.normalize(result.getBigDecimal("balance"))));
            }
        }
    }

    public PaymentResult pay(UUID sourceUuid, UUID targetUuid, CurrencyDefinition currency,
                             BigDecimal rawAmount) throws SQLException {
        if (sourceUuid.equals(targetUuid)) {
            return PaymentResult.failure(PaymentStatus.SELF);
        }
        BigDecimal amount = currency.normalize(rawAmount);
        if (amount.signum() <= 0 || amount.compareTo(currency.minimumPayment()) < 0) {
            return new PaymentResult(PaymentStatus.TOO_SMALL, null, null, null, amount, null, null);
        }

        UUID transactionId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                Map<UUID, LockedUser> users = lockUsers(connection, sourceUuid, targetUuid, currency);
                LockedUser source = users.get(sourceUuid);
                LockedUser target = users.get(targetUuid);
                if (source == null || target == null) {
                    connection.rollback();
                    return PaymentResult.failure(PaymentStatus.PLAYER_NOT_FOUND);
                }
                if (!SettingsCodec.paymentsEnabled(target.settings(), currency.id())) {
                    connection.rollback();
                    return new PaymentResult(PaymentStatus.PAYMENTS_DISABLED, null, source.profile(),
                        target.profile(), amount, source.balance(), target.balance());
                }
                if (source.balance().compareTo(amount) < 0) {
                    connection.rollback();
                    return new PaymentResult(PaymentStatus.INSUFFICIENT, null, source.profile(),
                        target.profile(), amount, source.balance(), target.balance());
                }

                BigDecimal newSource = currency.normalize(source.balance().subtract(amount));
                BigDecimal newTarget = currency.normalize(target.balance().add(amount));
                if (currency.hasMaximum() && newTarget.compareTo(currency.maximumBalance()) > 0) {
                    connection.rollback();
                    return new PaymentResult(PaymentStatus.TARGET_LIMIT, null, source.profile(),
                        target.profile(), amount, source.balance(), target.balance());
                }

                updateBalance(connection, sourceUuid, currency, newSource);
                updateBalance(connection, targetUuid, currency, newTarget);
                insertTransaction(connection, transactionId, "PAY", sourceUuid, sourceUuid, targetUuid,
                    currency.id(), amount, "COMMITTED");
                insertNotification(connection, targetUuid, transactionId, "PAY", currency.id(), amount,
                    source.profile().name());
                connection.commit();
                return new PaymentResult(PaymentStatus.SUCCESS, transactionId, source.profile(), target.profile(),
                    amount, newSource, newTarget);
            }
            catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
            finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Optional<PaymentsResult> payments(UUID uuid, CurrencyDefinition currency,
                                             Optional<Boolean> requested) throws SQLException {
        String select = "SELECT uuid, name, settings FROM `" + config.database().usersTable()
            + "` WHERE uuid = ? FOR UPDATE";
        String update = "UPDATE `" + config.database().usersTable() + "` SET settings = ? WHERE uuid = ?";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PlayerProfile profile;
                String settings;
                try (PreparedStatement statement = connection.prepareStatement(select)) {
                    statement.setString(1, uuid.toString());
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return Optional.empty();
                        }
                        profile = new PlayerProfile(readUuid(result, "uuid"), result.getString("name"));
                        settings = result.getString("settings");
                    }
                }
                boolean current = SettingsCodec.paymentsEnabled(settings, currency.id());
                boolean enabled = requested.orElse(!current);
                if (requested.isPresent() || enabled != current) {
                    try (PreparedStatement statement = connection.prepareStatement(update)) {
                        statement.setString(1, SettingsCodec.setPaymentsEnabled(settings, currency.id(), enabled));
                        statement.setString(2, uuid.toString());
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return Optional.of(new PaymentsResult(profile, enabled));
            }
            catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
            finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Optional<PaymentsResult> paymentsStatus(UUID uuid, CurrencyDefinition currency) throws SQLException {
        String sql = "SELECT uuid, name, settings FROM `" + config.database().usersTable()
            + "` WHERE uuid = ? LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                PlayerProfile profile = new PlayerProfile(readUuid(result, "uuid"), result.getString("name"));
                return Optional.of(new PaymentsResult(profile,
                    SettingsCodec.paymentsEnabled(result.getString("settings"), currency.id())));
            }
        }
    }

    public int triggerSync(Optional<UUID> uuid, String nodeId) throws SQLException {
        if (!hasColumn("last_modified") || !hasColumn("last_updated_by")) {
            return -1;
        }
        StringBuilder sql = new StringBuilder("UPDATE `").append(config.database().usersTable())
            .append("` SET last_modified = CURRENT_TIMESTAMP(6), last_updated_by = ?");
        if (uuid.isPresent()) {
            sql.append(" WHERE uuid = ?");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, nodeId);
            if (uuid.isPresent()) {
                statement.setString(2, uuid.get().toString());
            }
            return statement.executeUpdate();
        }
    }

    public CampaignPreview previewCampaign(Set<UUID> online) throws SQLException {
        long total;
        String sql = "SELECT COUNT(*) FROM `" + config.database().usersTable() + "`";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            total = result.getLong(1);
        }
        long onlineExisting = online.stream().filter(uuid -> {
            try {
                return findProfile(uuid).isPresent();
            }
            catch (SQLException ignored) {
                return false;
            }
        }).count();
        return new CampaignPreview(total, onlineExisting, Math.max(0, total - onlineExisting));
    }

    public CampaignResult runCampaign(UUID campaignId, String actor, CurrencyDefinition currency, BigDecimal rawAmount,
                                      Set<UUID> online, PluginConfig.OnlineMode mode,
                                      int batchSize) throws SQLException {
        BigDecimal amount = currency.normalize(rawAmount);
        createCampaign(campaignId, actor, currency.id(), amount);
        long cursor = 0;
        long paid = 0;
        long deferred = 0;
        long capped = 0;
        long failed = 0;

        while (true) {
            List<CampaignUser> batch = loadCampaignBatch(cursor, currency, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    for (CampaignUser user : batch) {
                        cursor = user.id();
                        boolean shouldDefer = mode == PluginConfig.OnlineMode.SAFE_DEFER_ONLINE
                            && online.contains(user.uuid());
                        if (shouldDefer) {
                            insertPendingGrant(connection, campaignId, user.uuid(), currency.id(), amount);
                            deferred++;
                            continue;
                        }
                        BigDecimal intended = currency.normalize(user.balance().add(amount));
                        BigDecimal updated = currency.cap(intended);
                        if (updated.compareTo(intended) < 0) {
                            capped++;
                        }
                        updateBalance(connection, user.uuid(), currency, updated);
                        insertNotification(connection, user.uuid(), campaignId, "PAYOFFLINE", currency.id(), amount,
                            actor);
                        paid++;
                    }
                    updateCampaign(connection, campaignId, "RUNNING", cursor, paid, deferred, capped, failed, false);
                    connection.commit();
                }
                catch (SQLException | RuntimeException exception) {
                    connection.rollback();
                    failed += batch.size();
                    updateCampaignFailure(campaignId, cursor, paid, deferred, capped, failed);
                    throw exception;
                }
                finally {
                    connection.setAutoCommit(true);
                }
            }
        }
        finishCampaign(campaignId, cursor, paid, deferred, capped, failed);
        insertCampaignTransaction(campaignId, actor, currency.id(), amount);
        return new CampaignResult(campaignId, paid, deferred, capped, failed);
    }

    public List<CampaignProgress> recoverableCampaigns() throws SQLException {
        String sql = "SELECT * FROM eev_campaigns WHERE status = 'RUNNING' ORDER BY created_at";
        List<CampaignProgress> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new CampaignProgress(UUID.fromString(rows.getString("campaign_id")),
                    rows.getString("status"), rows.getLong("paid_count"), rows.getLong("deferred_count"),
                    rows.getLong("capped_count"), rows.getLong("failed_count"), rows.getLong("cursor_id")));
            }
        }
        return result;
    }

    public int applyPendingGrants(UUID playerUuid, Map<String, CurrencyDefinition> currencies) throws SQLException {
        String sql = "SELECT campaign_id, currency, amount FROM eev_pending_grants "
            + "WHERE player_uuid = ? AND state = 'PENDING' ORDER BY created_at FOR UPDATE";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<PendingGrant> grants = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, playerUuid.toString());
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            grants.add(new PendingGrant(UUID.fromString(result.getString("campaign_id")),
                                result.getString("currency"), result.getBigDecimal("amount")));
                        }
                    }
                }
                int applied = 0;
                for (PendingGrant grant : grants) {
                    CurrencyDefinition currency = currencies.get(grant.currency());
                    if (currency == null) {
                        continue;
                    }
                    BigDecimal current = lockBalance(connection, playerUuid, currency);
                    BigDecimal updated = currency.cap(currency.normalize(current.add(grant.amount())));
                    updateBalance(connection, playerUuid, currency, updated);
                    markGrantApplied(connection, grant.campaignId(), playerUuid);
                    insertNotification(connection, playerUuid, grant.campaignId(), "PAYOFFLINE", currency.id(),
                        grant.amount(), "payoffline");
                    applied++;
                }
                connection.commit();
                return applied;
            }
            catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
            finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public List<Notification> pendingNotifications(UUID playerUuid, int limit) throws SQLException {
        String sql = "SELECT id, player_uuid, kind, currency, amount, source_name, created_at "
            + "FROM eev_notifications WHERE player_uuid = ? AND delivered_at IS NULL ORDER BY id LIMIT ?";
        List<Notification> notifications = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Timestamp created = result.getTimestamp("created_at");
                    notifications.add(new Notification(result.getLong("id"), readUuid(result, "player_uuid"),
                        result.getString("kind"), result.getString("currency"), result.getBigDecimal("amount"),
                        result.getString("source_name"),
                        created == null ? Instant.now() : created.toInstant()));
                }
            }
        }
        return notifications;
    }

    public void markNotificationsDelivered(Collection<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        String sql = "UPDATE eev_notifications SET delivered_at = CURRENT_TIMESTAMP(6) WHERE id IN ("
            + placeholders + ")";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (Long id : ids) {
                statement.setLong(index++, id);
            }
            statement.executeUpdate();
        }
    }

    public void markNotificationDelivered(UUID playerUuid, UUID transactionId) throws SQLException {
        String sql = "UPDATE eev_notifications SET delivered_at = CURRENT_TIMESTAMP(6) "
            + "WHERE player_uuid = ? AND transaction_id = ? AND delivered_at IS NULL";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, transactionId.toString());
            statement.executeUpdate();
        }
    }

    private Map<UUID, LockedUser> lockUsers(Connection connection, UUID first, UUID second,
                                            CurrencyDefinition currency) throws SQLException {
        String sql = "SELECT uuid, name, settings, `" + currency.column() + "` AS balance FROM `"
            + config.database().usersTable() + "` WHERE uuid IN (?, ?) ORDER BY uuid FOR UPDATE";
        Map<UUID, LockedUser> users = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, first.toString());
            statement.setString(2, second.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID uuid = readUuid(result, "uuid");
                    users.put(uuid, new LockedUser(new PlayerProfile(uuid, result.getString("name")),
                        currency.normalize(result.getBigDecimal("balance")), result.getString("settings")));
                }
            }
        }
        return users;
    }

    private BigDecimal lockBalance(Connection connection, UUID uuid, CurrencyDefinition currency)
        throws SQLException {
        String sql = "SELECT `" + currency.column() + "` FROM `" + config.database().usersTable()
            + "` WHERE uuid = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("ExcellentEconomy user not found: " + uuid);
                }
                return currency.normalize(result.getBigDecimal(1));
            }
        }
    }

    private void updateBalance(Connection connection, UUID uuid, CurrencyDefinition currency,
                               BigDecimal balance) throws SQLException {
        String sql = "UPDATE `" + config.database().usersTable() + "` SET `" + currency.column()
            + "` = ? WHERE uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBigDecimal(1, balance);
            statement.setString(2, uuid.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Failed to update ExcellentEconomy balance for " + uuid);
            }
        }
    }

    private void insertTransaction(Connection connection, UUID id, String type, UUID actor, UUID source, UUID target,
                                   String currency, BigDecimal amount, String status) throws SQLException {
        String sql = "INSERT INTO eev_transactions "
            + "(transaction_id, transaction_type, actor_uuid, source_uuid, target_uuid, currency, amount, status) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            statement.setString(2, type);
            statement.setString(3, actor == null ? null : actor.toString());
            statement.setString(4, source == null ? null : source.toString());
            statement.setString(5, target == null ? null : target.toString());
            statement.setString(6, currency);
            statement.setBigDecimal(7, amount);
            statement.setString(8, status);
            statement.executeUpdate();
        }
    }

    private void insertNotification(Connection connection, UUID player, UUID transactionId, String kind,
                                    String currency, BigDecimal amount, String source) throws SQLException {
        String sql = "INSERT IGNORE INTO eev_notifications "
            + "(player_uuid, transaction_id, kind, currency, amount, source_name) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setString(2, transactionId == null ? null : transactionId.toString());
            statement.setString(3, kind);
            statement.setString(4, currency);
            statement.setBigDecimal(5, amount);
            statement.setString(6, source);
            statement.executeUpdate();
        }
    }

    private boolean hasColumn(String column) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet result = metadata.getColumns(connection.getCatalog(), null,
                config.database().usersTable(), column)) {
                return result.next();
            }
        }
    }

    private void createCampaign(UUID id, String actor, String currency, BigDecimal amount) throws SQLException {
        String sql = "INSERT INTO eev_campaigns (campaign_id, actor, currency, amount, status) "
            + "VALUES (?, ?, ?, ?, 'RUNNING')";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            statement.setString(2, actor);
            statement.setString(3, currency);
            statement.setBigDecimal(4, amount);
            statement.executeUpdate();
        }
    }

    private List<CampaignUser> loadCampaignBatch(long cursor, CurrencyDefinition currency, int batchSize)
        throws SQLException {
        String sql = "SELECT id, uuid, `" + currency.column() + "` AS balance FROM `"
            + config.database().usersTable() + "` WHERE id > ? ORDER BY id LIMIT ?";
        List<CampaignUser> users = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, cursor);
            statement.setInt(2, batchSize);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    users.add(new CampaignUser(result.getLong("id"), readUuid(result, "uuid"),
                        currency.normalize(result.getBigDecimal("balance"))));
                }
            }
        }
        return users;
    }

    private void insertPendingGrant(Connection connection, UUID campaign, UUID player, String currency,
                                    BigDecimal amount) throws SQLException {
        String sql = "INSERT IGNORE INTO eev_pending_grants "
            + "(campaign_id, player_uuid, currency, amount) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, campaign.toString());
            statement.setString(2, player.toString());
            statement.setString(3, currency);
            statement.setBigDecimal(4, amount);
            statement.executeUpdate();
        }
    }

    private void markGrantApplied(Connection connection, UUID campaign, UUID player) throws SQLException {
        String sql = "UPDATE eev_pending_grants SET state = 'APPLIED', applied_at = CURRENT_TIMESTAMP(6) "
            + "WHERE campaign_id = ? AND player_uuid = ? AND state = 'PENDING'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, campaign.toString());
            statement.setString(2, player.toString());
            statement.executeUpdate();
        }
    }

    private void updateCampaign(Connection connection, UUID campaign, String status, long cursor,
                                long paid, long deferred, long capped, long failed, boolean complete)
        throws SQLException {
        String sql = "UPDATE eev_campaigns SET status = ?, cursor_id = ?, paid_count = ?, deferred_count = ?, "
            + "capped_count = ?, failed_count = ?, completed_at = "
            + (complete ? "CURRENT_TIMESTAMP(6)" : "completed_at") + " WHERE campaign_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setLong(2, cursor);
            statement.setLong(3, paid);
            statement.setLong(4, deferred);
            statement.setLong(5, capped);
            statement.setLong(6, failed);
            statement.setString(7, campaign.toString());
            statement.executeUpdate();
        }
    }

    private void updateCampaignFailure(UUID campaign, long cursor, long paid, long deferred,
                                       long capped, long failed) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            updateCampaign(connection, campaign, "FAILED", cursor, paid, deferred, capped, failed, false);
        }
    }

    private void finishCampaign(UUID campaign, long cursor, long paid, long deferred,
                                long capped, long failed) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            updateCampaign(connection, campaign, "COMPLETED", cursor, paid, deferred, capped, failed, true);
        }
    }

    private void insertCampaignTransaction(UUID campaign, String actor, String currency,
                                           BigDecimal amount) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            insertTransaction(connection, campaign, "PAYOFFLINE", null, null, null, currency, amount, "COMMITTED");
        }
    }

    private static UUID readUuid(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof byte[] bytes && bytes.length == 16) {
            long high = 0;
            long low = 0;
            for (int index = 0; index < 8; index++) {
                high = (high << 8) | (bytes[index] & 0xffL);
            }
            for (int index = 8; index < 16; index++) {
                low = (low << 8) | (bytes[index] & 0xffL);
            }
            return new UUID(high, low);
        }
        return UUID.fromString(String.valueOf(value));
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private record LockedUser(PlayerProfile profile, BigDecimal balance, String settings) {
    }

    private record CampaignUser(long id, UUID uuid, BigDecimal balance) {
    }

    private record PendingGrant(UUID campaignId, String currency, BigDecimal amount) {
    }
}
