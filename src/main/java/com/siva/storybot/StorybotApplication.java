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

        String jdbcUrl = env("DB_URL");
        String username = env("DB_USERNAME");
        String password = env("DB_PASSWORD");

        if (jdbcUrl != null) {

            properties.put("spring.datasource.url", jdbcUrl);

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
