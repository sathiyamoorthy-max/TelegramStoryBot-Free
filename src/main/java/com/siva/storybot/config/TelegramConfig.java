package com.siva.storybot.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Data
public class TelegramConfig {

    // =====================================
    // BOT
    // =====================================

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.bot.token}")
    private String botToken;

    // =====================================
    // OWNER
    // =====================================

    @Value("${telegram.owner.id}")
    private Long ownerId;

    @Value("${telegram.owner.username:owner}")
    private String ownerUsername;

    @Value("${telegram.admin.ids:}")
    private String adminIds;

    public boolean isConfiguredAdmin(Long telegramId) {
        if (telegramId == null || adminIds == null || adminIds.isBlank()) {
            return false;
        }

        for (String rawId : adminIds.split(",")) {
            String value = rawId == null ? "" : rawId.trim();

            if (value.isBlank()) {
                continue;
            }

            try {
                if (telegramId.equals(Long.valueOf(value))) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed optional bootstrap IDs.
            }
        }

        return false;
    }
}