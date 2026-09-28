package com.siva.storybot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyMigrationRunner implements ApplicationRunner {

    private final DataSource targetDataSource;

    @Value("${legacy.migration.enabled:false}")
    private boolean enabled;

    @Value("${legacy.datasource.url:}")
    private String sourceUrl;

    @Value("${legacy.datasource.username:}")
    private String sourceUsername;

    @Value("${legacy.datasource.password:}")
    private String sourcePassword;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) {
            return;
        }

        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalStateException("Legacy migration source is not configured");
        }

        try (Connection source = DriverManager.getConnection(sourceUrl, sourceUsername, sourcePassword);
             Connection target = targetDataSource.getConnection();
             Statement statement = source.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM stories")) {

            if (resultSet.next()) {
                log.info("Legacy database reachable. stories={}", resultSet.getLong(1));
            }

            if (target == null) {
                throw new IllegalStateException("Target database is unavailable");
            }
        }
    }
}
