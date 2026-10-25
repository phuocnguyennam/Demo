package com.demo.auth;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfig {
    @Bean
    public DataSource dataSource(
            @Value("${DATABASE_URL:}") String databaseUrl,
            @Value("${DB_HOST:localhost}") String host,
            @Value("${DB_PORT:5432}") int port,
            @Value("${DB_USER:postgres}") String user,
            @Value("${DB_PASSWORD:postgres}") String password,
            @Value("${DB_NAME:mydb}") String database) throws UnsupportedEncodingException {
        HikariConfig config = new HikariConfig();
        if (databaseUrl != null && !databaseUrl.trim().isEmpty()) {
            configureFromDatabaseUrl(config, databaseUrl.trim(), user, password);
        } else {
            config.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
            config.setUsername(user);
            config.setPassword(password);
        }
        return new HikariDataSource(config);
    }

    private void configureFromDatabaseUrl(
            HikariConfig config, String databaseUrl, String fallbackUser, String fallbackPassword)
            throws UnsupportedEncodingException {
        if (databaseUrl.startsWith("jdbc:")) {
            config.setJdbcUrl(databaseUrl);
            config.setUsername(fallbackUser);
            config.setPassword(fallbackPassword);
            return;
        }

        String normalizedUrl = databaseUrl.startsWith("postgres://")
                ? "postgresql://" + databaseUrl.substring("postgres://".length())
                : databaseUrl;
        URI uri = URI.create(normalizedUrl);
        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() >= 0) {
            jdbcUrl.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath();
        if (path != null) {
            jdbcUrl.append(path);
        }
        if (uri.getRawQuery() != null) {
            jdbcUrl.append('?').append(uri.getRawQuery());
        }
        config.setJdbcUrl(jdbcUrl.toString());

        String username = fallbackUser;
        String password = fallbackPassword;
        if (uri.getRawUserInfo() != null) {
            String[] credentials = uri.getRawUserInfo().split(":", 2);
            username = URLDecoder.decode(credentials[0].replace("+", "%2B"), "UTF-8");
            if (credentials.length == 2) {
                password = URLDecoder.decode(credentials[1].replace("+", "%2B"), "UTF-8");
            }
        }
        config.setUsername(username);
        config.setPassword(password);
    }
}
