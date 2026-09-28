package com.siva.storybot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyMigrationRunner implements ApplicationRunner {

    private static final String MIGRATION_KEY = "original-storybot-data-v1";

    private final DataSource targetDataSource;

    @Value("${LEGACY_DB_MIGRATE:false}")
    private boolean enabled;

    @Value("${LEGACY_DB_URL:}")
    private String sourceUrl;

    @Value("${LEGACY_DB_USERNAME:}")
    private String sourceUsername;

    @Value("${LEGACY_DB_PASSWORD:}")
    private String sourcePassword;

    @Override
    public void run(ApplicationArguments args) throws Exception {

        if (!enabled || Boolean.parseBoolean(System.getenv().getOrDefault("LEGACY_DB_SUSPEND", "true"))) {
            return;
        }

        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalStateException("Legacy migration source is not configured");
        }

        try (Connection source = DriverManager.getConnection(sourceUrl, sourceUsername, sourcePassword);
             Connection target = targetDataSource.getConnection()) {

            source.setReadOnly(true);
            ensureMigrationStateTable(target);

            if (alreadyMigrated(target)) {
                log.info("Original StoryBot data migration already completed; skipping.");
                return;
            }

            target.setAutoCommit(false);

            try {
                MigrationContext context = new MigrationContext();

                int users = migrateUsers(source, target, context);
                int stories = migrateStories(source, target, context);
                int episodes = migrateEpisodes(source, target, context);
                int subscriptions = migrateSubscriptions(source, target, context);
                int globalTrials = migrateGlobalTrials(source, target);
                int accessRows = migrateStoryAccess(source, target, context);
                int rewards = migrateRewardTrials(source, target, context);
                int usage = migrateEpisodeUsage(source, target, context);

                markMigrated(
                        target,
                        "users=" + users
                                + ", stories=" + stories
                                + ", episodes=" + episodes
                                + ", subscriptions=" + subscriptions
                                + ", globalTrials=" + globalTrials
                                + ", storyAccess=" + accessRows
                                + ", rewards=" + rewards
                                + ", usage=" + usage);

                target.commit();

                log.info(
                        "Original StoryBot data migration completed. users={} stories={} episodes={} subscriptions={} globalTrials={} storyAccess={} rewards={} usage={}",
                        users, stories, episodes, subscriptions, globalTrials, accessRows, rewards, usage);

            } catch (Exception e) {
                target.rollback();
                throw e;
            } finally {
                target.setAutoCommit(true);
            }
        }
    }

    private int migrateUsers(Connection source, Connection target, MigrationContext context) throws SQLException {

        String sql = """
                INSERT INTO telegram_users (
                    telegram_id, chat_id, username, first_name, last_name,
                    language_code, premium_user, bot, joined_at, last_active_at, role
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (telegram_id) DO UPDATE SET
                    chat_id = EXCLUDED.chat_id,
                    username = EXCLUDED.username,
                    first_name = EXCLUDED.first_name,
                    last_name = EXCLUDED.last_name,
                    language_code = EXCLUDED.language_code,
                    premium_user = EXCLUDED.premium_user,
                    bot = EXCLUDED.bot,
                    joined_at = EXCLUDED.joined_at,
                    last_active_at = EXCLUDED.last_active_at,
                    role = EXCLUDED.role
                RETURNING id
                """;

        int count = 0;

        try (Statement read = source.createStatement();
             ResultSet rows = read.executeQuery("SELECT * FROM telegram_users ORDER BY id");
             PreparedStatement write = target.prepareStatement(sql)) {

            while (rows.next()) {
                Row row = Row.from(rows);

                Long sourceId = row.longValue("id");
                Long telegramId = row.longValue("telegram_id", "telegramid");

                if (sourceId == null || telegramId == null) {
                    continue;
                }

                Long chatId = firstNonNull(row.longValue("chat_id", "chatid"), telegramId);
                String role = defaultString(row.stringValue("role"), "USER");

                write.setLong(1, telegramId);
                write.setLong(2, chatId);
                setNullable(write, 3, row.stringValue("username"));
                setNullable(write, 4, row.stringValue("first_name", "firstname"));
                setNullable(write, 5, row.stringValue("last_name", "lastname"));
                setNullable(write, 6, row.stringValue("language_code", "languagecode"));
                write.setBoolean(7, defaultBoolean(row.booleanValue("premium_user", "premiumuser"), false));
                write.setBoolean(8, defaultBoolean(row.booleanValue("bot"), false));
                write.setTimestamp(9, defaultTimestamp(row.timestampValue("joined_at", "joinedat")));
                write.setTimestamp(10, defaultTimestamp(row.timestampValue("last_active_at", "lastactiveat")));
                write.setString(11, role);

                try (ResultSet inserted = write.executeQuery()) {
                    inserted.next();
                    Long targetId = inserted.getLong(1);
                    context.userIds.put(sourceId, targetId);
                    context.userRoles.put(sourceId, role);
                }

                count++;
            }
        }

        log.info("Migrated telegram_users rows={}", count);
        return count;
    }

    private int migrateStories(Connection source, Connection target, MigrationContext context) throws SQLException {

        String sql = """
                INSERT INTO stories (
                    telegram_chat_id, title, active, created_at, telegram_username,
                    chat_type, description, invite_link, is_completed, story_icon_file_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (telegram_chat_id) DO UPDATE SET
                    title = EXCLUDED.title,
                    active = EXCLUDED.active,
                    created_at = EXCLUDED.created_at,
                    telegram_username = EXCLUDED.telegram_username,
                    chat_type = EXCLUDED.chat_type,
                    description = EXCLUDED.description,
                    invite_link = EXCLUDED.invite_link,
                    is_completed = EXCLUDED.is_completed,
                    story_icon_file_id = EXCLUDED.story_icon_file_id
                RETURNING id
                """;

        int count = 0;

        try (Statement read = source.createStatement();
             ResultSet rows = read.executeQuery("SELECT * FROM stories ORDER BY id");
             PreparedStatement write = target.prepareStatement(sql)) {

            while (rows.next()) {
                Row row = Row.from(rows);

                Long sourceId = row.longValue("id");
                Long telegramChatId = row.longValue("telegram_chat_id", "telegramchatid");

                if (sourceId == null || telegramChatId == null) {
                    continue;
                }

                String title = defaultString(row.stringValue("title"), "Story " + telegramChatId);

                write.setLong(1, telegramChatId);
                write.setString(2, title);
                write.setBoolean(3, defaultBoolean(row.booleanValue("active"), true));
                write.setTimestamp(4, defaultTimestamp(row.timestampValue("created_at", "createdat")));
                setNullable(write, 5, row.stringValue("telegram_username", "telegramusername"));
                setNullable(write, 6, row.stringValue("chat_type", "chattype"));
                setNullable(write, 7, row.stringValue("description"));
                setNullable(write, 8, row.stringValue("invite_link", "invitelink"));
                setNullableBoolean(write, 9, row.booleanValue("is_completed", "iscompleted"));
                setNullable(write, 10, row.stringValue("story_icon_file_id"));

                try (ResultSet inserted = write.executeQuery()) {
                    inserted.next();
                    context.storyIds.put(sourceId, inserted.getLong(1));
                }

                count++;
            }
        }

        log.info("Migrated stories rows={}", count);
        return count;
    }

    private int migrateEpisodes(Connection source, Connection target, MigrationContext context) throws SQLException {

        String sql = """
                INSERT INTO episodes (
                    story_id, episode_no, episode_no_numeric, title,
                    telegram_file_id, telegram_message_id, duration_seconds,
                    file_size, is_episode_detected, uploaded_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        int count = 0;

        try (Statement read = source.createStatement();
             ResultSet rows = read.executeQuery("SELECT * FROM episodes ORDER BY id");
             PreparedStatement write = target.prepareStatement(sql)) {

            while (rows.next()) {
                Row row = Row.from(rows);
                Long targetStoryId = context.storyIds.get(row.longValue("story_id"));

                if (targetStoryId == null) {
                    log.warn("Skipping legacy episode because story mapping is missing. sourceEpisodeId={}", row.longValue("id"));
                    continue;
                }

                write.setLong(1, targetStoryId);
                setNullable(write, 2, row.stringValue("episode_no", "episodeno"));
                setNullableInteger(write, 3, row.intValue("episode_no_numeric", "episodenonumeric"));
                setNullable(write, 4, row.stringValue("title"));
                setNullable(write, 5, row.stringValue("telegram_file_id", "telegramfileid"));
                setNullableLong(write, 6, row.longValue("telegram_message_id", "telegrammessageid"));
                setNullableInteger(write, 7, row.intValue("duration_seconds", "durationseconds"));
                setNullableLong(write, 8, row.longValue("file_size", "filesize"));
                setNullableBoolean(write, 9, row.booleanValue("is_episode_detected", "isepisodedetected"));
                setNullableTimestamp(write, 10, row.timestampValue("uploaded_at", "uploadedat"));

                write.addBatch();
                count++;

                if (count % 500 == 0) {
                    write.executeBatch();
                }
            }

            if (count % 500 != 0) {
                write.executeBatch();
            }
        }

        log.info("Migrated episodes rows={}", count);
        return count;
    }

    private int migrateSubscriptions(Connection source, Connection target, MigrationContext context) throws SQLException {

        String sql = """
                INSERT INTO subscriptions (
                    telegram_user_id, plan, billing_type, amount, payment_done,
                    status, trial, start_date, expiry_date, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        int count = 0;

        try (Statement read = source.createStatement();
             ResultSet rows = read.executeQuery("SELECT * FROM subscriptions ORDER BY id");
             PreparedStatement write = target.prepareStatement(sql)) {

            while (rows.next()) {
                Row row = Row.from(rows);
                Long targetUserId = context.userIds.get(row.longValue("telegram_user_id"));

                if (targetUserId == null) {
                    continue;
                }

                write.setLong(1, targetUserId);
                write.setString(2, defaultString(row.stringValue("plan"), "FREE"));
                write.setString(3, defaultString(row.stringValue("billing_type", "billingtype"), "FREE"));
                write.setBigDecimal(4, defaultDecimal(row.decimalValue("amount")));
                write.setBoolean(5, defaultBoolean(row.booleanValue("payment_done", "paymentdone"), false));
                write.setString(6, defaultString(row.stringValue("status"), "EXPIRED"));
                write.setBoolean(7, defaultBoolean(row.booleanValue("trial"), false));
                write.setDate(8, defaultDate(row.dateValue("start_date", "startdate")));
                write.setDate(9, defaultDate(row.dateValue("expiry_date", "expirydate")));
                write.setTimestamp(10, defaultTimestamp(row.timestampValue("created_at", "createdat")));
                write.setTimestamp(11, defaultTimestamp(row.timestampValue("updated_at", "updatedat")));

                write.addBatch();
                count++;

                if (count % 250 == 0) {
                    write.executeBatch();
                }
            }

            if (count % 250 != 0) {
                write.executeBatch();
            }
        }

        log.info("Migrated subscriptions rows={}", count);
        return count;
    }

    private int migrateGlobalTrials(Connection source, Connection target) throws SQLException {

        String sql = """
                INSERT INTO global_trials (
                    enabled, trial_days, start_date, end_date, enabled_by, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        int count = 0;

        try (Statement read = source.createStatement();
             ResultSet rows = read.executeQuery("SELECT * FROM global_trials ORDER BY id");
             PreparedStatement write = target.prepareStatement(sql)) {

            while (rows.next()) {
                Row row = Row.from(rows);

                write.setBoolean(1, defaultBoolean(row.booleanValue("enabled"), false));
                write.setInt(2, firstNonNull(row.intValue("trial_days", "trialdays"), 7));
                write.setTimestamp(3, defaultTimestamp(row.timestampValue("start_date", "startdate")));
                write.setTimestamp(4, defaultTimestamp(row.timestampValue("end_date", "enddate")));
                setNullableLong(write, 5, row.longValue("enabled_by", "enabledby"));
                write.setTimestamp(6, defaultTimestamp(row.timestampValue("created_at", "createdat")));
                write.setTimestamp(7, defaultTimestamp(row.timestampValue("updated_at", "updatedat")));

                write.addBatch();
                count++;
            }

            if (count > 0) {
                write.executeBatch();
            }
        }

        log.info("Migrated global_trials rows={}", count);
        return count;
    }

    private int migrateStoryAccess(Connection source, Connection target, MigrationContext context) throws SQLException {

        String sql = """
                INSERT INTO user_story_access (
                    telegram_user_id, story_id, granted_by_user_id,
                    granted_by_role, active, granted_at, revoked_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (telegram_user_id, story_id) DO UPDATE SET
                    granted_by_user_id = EXCLUDED.granted_by_user_id,
                    granted_by_role = EXCLUDED.granted_by_role,
                    active = EXCLUDED.active,
                    granted_at = EXCLUDED.granted_at,
                    revoked_at = EXCLUDED.revoked_at,
                    updated_at = EXCLUDED.updated_at
                """;

        int count = 0;

        try (Statement read = source.createStatement();
             ResultSet rows = read.executeQuery("SELECT * FROM user_story_access ORDER BY id");
             PreparedStatement write = target.prepareStatement(sql)) {

            while (rows.next()) {
                Row row = Row.from(rows);

                Long sourceUserId = row.longValue("telegram_user_id");
                Long sourceStoryId = row.longValue("story_id");
                Long targetUserId = context.userIds.get(sourceUserId);
                Long targetStoryId = context.storyIds.get(sourceStoryId);

                if (targetUserId == null || targetStoryId == null) {
                    continue;
                }

                Long sourceGrantedBy = row.longValue("granted_by_user_id");
                Long targetGrantedBy = sourceGrantedBy == null ? null : context.userIds.get(sourceGrantedBy);

                String grantedByRole = row.stringValue("granted_by_role");

                if (grantedByRole == null && sourceGrantedBy != null) {
                    grantedByRole = context.userRoles.get(sourceGrantedBy);
                }

                write.setLong(1, targetUserId);
                write.setLong(2, targetStoryId);
                setNullableLong(write, 3, targetGrantedBy);
                write.setString(4, defaultString(grantedByRole, "OWNER"));
                write.setBoolean(5, defaultBoolean(row.booleanValue("active"), true));
                write.setTimestamp(6, defaultTimestamp(row.timestampValue("granted_at", "grantedat")));
                setNullableTimestamp(write, 7, row.timestampValue("revoked_at", "revokedat"));
                write.setTimestamp(8, defaultTimestamp(row.timestampValue("updated_at", "updatedat")));

                write.addBatch();
                count++;

                if (count % 250 == 0) {
                    write.executeBatch();
                }
            }

            if (count % 250 != 0) {
                write.executeBatch();
            }
        }

        log.info("Migrated user_story_access rows={}", count);
        return count;
    }

    private int migrateRewardTrials(Connection source, Connection target, MigrationContext context) throws SQLException {

        String sql = """
                INSERT INTO reward_trials (
                    telegram_user_id, selected_story_id, token, status, provider,
                    short_url, created_at, link_expires_at, activated_at, expires_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (token) DO NOTHING
                """;

        int count = 0;

        try (Statement read = source.createStatement();
             ResultSet rows = read.executeQuery("SELECT * FROM reward_trials ORDER BY id");
             PreparedStatement write = target.prepareStatement(sql)) {

            while (rows.next()) {
                Row row = Row.from(rows);
                Long targetUserId = context.userIds.get(row.longValue("telegram_user_id"));

                if (targetUserId == null) {
                    continue;
                }

                Long sourceStoryId = row.longValue("selected_story_id");
                Long targetStoryId = sourceStoryId == null ? null : context.storyIds.get(sourceStoryId);
                String token = row.stringValue("token");

                if (token == null || token.isBlank()) {
                    continue;
                }

                write.setLong(1, targetUserId);
                setNullableLong(write, 2, targetStoryId);
                write.setString(3, token);
                write.setString(4, defaultString(row.stringValue("status"), "EXPIRED"));
                write.setString(5, defaultString(row.stringValue("provider"), "SHRTFLY"));
                setNullable(write, 6, row.stringValue("short_url"));
                write.setTimestamp(7, defaultTimestamp(row.timestampValue("created_at")));
                write.setTimestamp(8, defaultTimestamp(row.timestampValue("link_expires_at")));
                setNullableTimestamp(write, 9, row.timestampValue("activated_at"));
                setNullableTimestamp(write, 10, row.timestampValue("expires_at"));
                write.setTimestamp(11, defaultTimestamp(row.timestampValue("updated_at")));

                write.addBatch();
                count++;

                if (count % 250 == 0) {
                    write.executeBatch();
                }
            }

            if (count % 250 != 0) {
                write.executeBatch();
            }
        }

        log.info("Migrated reward_trials rows={}", count);
        return count;
    }

    private int migrateEpisodeUsage(Connection source, Connection target, MigrationContext context) throws SQLException {

        String sql = """
                INSERT INTO episode_usage (
                    telegram_user_id, usage_date, daily_count,
                    hour_window_start, hourly_count, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (telegram_user_id) DO UPDATE SET
                    usage_date = EXCLUDED.usage_date,
                    daily_count = EXCLUDED.daily_count,
                    hour_window_start = EXCLUDED.hour_window_start,
                    hourly_count = EXCLUDED.hourly_count,
                    updated_at = EXCLUDED.updated_at
                """;

        int count = 0;

        try (Statement read = source.createStatement();
             ResultSet rows = read.executeQuery("SELECT * FROM episode_usage ORDER BY id");
             PreparedStatement write = target.prepareStatement(sql)) {

            while (rows.next()) {
                Row row = Row.from(rows);
                Long targetUserId = context.userIds.get(row.longValue("telegram_user_id"));

                if (targetUserId == null) {
                    continue;
                }

                write.setLong(1, targetUserId);
                write.setDate(2, defaultDate(row.dateValue("usage_date")));
                write.setInt(3, firstNonNull(row.intValue("daily_count"), 0));
                write.setTimestamp(4, defaultTimestamp(row.timestampValue("hour_window_start")));
                write.setInt(5, firstNonNull(row.intValue("hourly_count"), 0));
                write.setTimestamp(6, defaultTimestamp(row.timestampValue("updated_at")));

                write.addBatch();
                count++;
            }

            if (count > 0) {
                write.executeBatch();
            }
        }

        log.info("Migrated episode_usage rows={}", count);
        return count;
    }

    private void ensureMigrationStateTable(Connection target) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS legacy_migration_state (
                        migration_key VARCHAR(100) PRIMARY KEY,
                        completed_at TIMESTAMP NOT NULL,
                        details TEXT
                    )
                    """);
        }
    }

    private boolean alreadyMigrated(Connection target) throws SQLException {
        try (PreparedStatement statement = target.prepareStatement(
                "SELECT 1 FROM legacy_migration_state WHERE migration_key = ?")) {
            statement.setString(1, MIGRATION_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void markMigrated(Connection target, String details) throws SQLException {
        try (PreparedStatement statement = target.prepareStatement("""
                INSERT INTO legacy_migration_state (migration_key, completed_at, details)
                VALUES (?, CURRENT_TIMESTAMP, ?)
                """)) {
            statement.setString(1, MIGRATION_KEY);
            statement.setString(2, details);
            statement.executeUpdate();
        }
    }

    private static <T> T firstNonNull(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean defaultBoolean(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private static BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Timestamp defaultTimestamp(Timestamp value) {
        return value == null ? Timestamp.valueOf(LocalDateTime.now()) : value;
    }

    private static Date defaultDate(Date value) {
        return value == null ? Date.valueOf(LocalDate.now()) : value;
    }

    private static void setNullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }

    private static void setNullableInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER);
        else statement.setInt(index, value);
    }

    private static void setNullableBoolean(PreparedStatement statement, int index, Boolean value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BOOLEAN);
        else statement.setBoolean(index, value);
    }

    private static void setNullableTimestamp(PreparedStatement statement, int index, Timestamp value) throws SQLException {
        if (value == null) statement.setNull(index, Types.TIMESTAMP);
        else statement.setTimestamp(index, value);
    }

    private static final class MigrationContext {
        private final Map<Long, Long> userIds = new HashMap<>();
        private final Map<Long, String> userRoles = new HashMap<>();
        private final Map<Long, Long> storyIds = new HashMap<>();
    }

    private static final class Row {

        private final Map<String, Object> values;

        private Row(Map<String, Object> values) {
            this.values = values;
        }

        static Row from(ResultSet resultSet) throws SQLException {
            ResultSetMetaData metaData = resultSet.getMetaData();
            Map<String, Object> values = new HashMap<>();

            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                String label = metaData.getColumnLabel(i);
                values.put(normalize(label), resultSet.getObject(i));
            }

            return new Row(values);
        }

        Object value(String... names) {
            for (String name : names) {
                Object value = values.get(normalize(name));
                if (value != null) return value;
            }
            return null;
        }

        String stringValue(String... names) {
            Object value = value(names);
            return value == null ? null : value.toString();
        }

        Long longValue(String... names) {
            Object value = value(names);
            if (value == null) return null;
            if (value instanceof Number number) return number.longValue();
            return Long.valueOf(value.toString());
        }

        Integer intValue(String... names) {
            Object value = value(names);
            if (value == null) return null;
            if (value instanceof Number number) return number.intValue();
            return Integer.valueOf(value.toString());
        }

        Boolean booleanValue(String... names) {
            Object value = value(names);
            if (value == null) return null;
            if (value instanceof Boolean bool) return bool;
            if (value instanceof Number number) return number.intValue() != 0;
            if (value instanceof byte[] bytes) return bytes.length > 0 && bytes[0] != 0;
            String text = value.toString().trim();
            return "1".equals(text)
                    || "true".equalsIgnoreCase(text)
                    || "yes".equalsIgnoreCase(text);
        }

        BigDecimal decimalValue(String... names) {
            Object value = value(names);
            if (value == null) return null;
            if (value instanceof BigDecimal decimal) return decimal;
            if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
            return new BigDecimal(value.toString());
        }

        Timestamp timestampValue(String... names) {
            Object value = value(names);
            if (value == null) return null;
            if (value instanceof Timestamp timestamp) return timestamp;
            if (value instanceof java.util.Date date) return new Timestamp(date.getTime());
            if (value instanceof LocalDateTime dateTime) return Timestamp.valueOf(dateTime);
            return Timestamp.valueOf(value.toString());
        }

        Date dateValue(String... names) {
            Object value = value(names);
            if (value == null) return null;
            if (value instanceof Date date) return date;
            if (value instanceof java.util.Date date) return new Date(date.getTime());
            if (value instanceof LocalDate localDate) return Date.valueOf(localDate);
            return Date.valueOf(value.toString());
        }

        private static String normalize(String value) {
            return value == null
                    ? ""
                    : value.replace("_", "").toLowerCase(Locale.ROOT);
        }
    }
}
