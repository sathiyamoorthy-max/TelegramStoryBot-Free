package com.siva.storybot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
@EnableScheduling
public class StorybotApplication {

    public static void main(String[] args) {

        SpringApplication application = new SpringApplication(StorybotApplication.class);

        Map<String, Object> defaults = resolveDatabaseProperties();

        if (!defaults.isEmpty()) {
            application.setDefaultProperties(defaults);
        }

        application.run(args);
    }

    private static Map<String, Object> resolveDatabaseProperties() {

        Map<String, Object> properties = new HashMap<>();

        boolean useOriginalDatabase = Boolean.parseBoolean(
                System.getenv().getOrDefault("ORIGINAL_DB_ENABLED", "false"));

        if (useOriginalDatabase) {

            String url = env("LEGACY_DB_URL");
            String username = env("LEGACY_DB_USERNAME");
            String password = env("LEGACY_DB_PASSWORD");

            if (url == null || username == null || password == null) {
                throw new IllegalStateException(
                        "ORIGINAL_DB_ENABLED=true but legacy database environment variables are incomplete");
            }

            properties.put("spring.datasource.url", url);
            properties.put("spring.datasource.username", username);
            properties.put("spring.datasource.password", password);

            if (url.startsWith("jdbc:mysql:")) {
                properties.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
            }

            return properties;
        }

        String jdbcUrl = env("DB_URL");

        if (jdbcUrl != null) {

            properties.put("spring.datasource.url", jdbcUrl);

            String username = env("DB_USERNAME");
            String password = env("DB_PASSWORD");

            if (username != null) {
                properties.put("spring.datasource.username", username);
            }

            if (password != null) {
                properties.put("spring.datasource.password", password);
            }

            return properties;
        }

        String databaseUrl = env("DATABASE_URL");

        if (databaseUrl == null) {
            return properties;
        }

        URI uri = URI.create(databaseUrl);

        String scheme = uri.getScheme();

        if (!"postgres".equalsIgnoreCase(scheme)
                && !"postgresql".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("DATABASE_URL must be a PostgreSQL URL");
        }

        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");

        properties.put(
                "spring.datasource.url",
                "jdbc:postgresql://" + uri.getHost() + ":" + port + "/" + database);
        properties.put("spring.datasource.driver-class-name", "org.postgresql.Driver");

        String userInfo = uri.getRawUserInfo();

        if (userInfo != null && !userInfo.isBlank()) {

            String[] parts = userInfo.split(":", 2);

            properties.put(
                    "spring.datasource.username",
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8));

            if (parts.length > 1) {
                properties.put(
                        "spring.datasource.password",
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }

        return properties;
    }

    private static String env(String name) {

        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}
