package com.example.ts15quarkusbanking.runtime;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Opens JDBC connections; replace with a pooled DataSource in production. */
@FunctionalInterface
public interface ConnectionFactory {
    Connection open() throws SQLException;

    /** From a libpq-style URL: postgres://user:password@host:5432/database[?sslmode=require] */
    static ConnectionFactory fromUrl(String databaseUrl) {
        URI uri = URI.create(databaseUrl.replaceFirst("^postgres(ql)?://", "postgresql://"));
        String jdbc = jdbcUrl(uri);
        String[] userInfo = uri.getRawUserInfo() == null ? new String[0] : uri.getRawUserInfo().split(":", 2);
        String user = userInfo.length > 0 ? URLDecoder.decode(userInfo[0], StandardCharsets.UTF_8) : null;
        String password = userInfo.length > 1 ? URLDecoder.decode(userInfo[1], StandardCharsets.UTF_8) : null;
        return () -> DriverManager.getConnection(jdbc, user, password);
    }

    /** JDBC URL without credentials; query parameters such as sslmode (libpq semantics in pgjdbc) are kept. */
    static String jdbcUrl(URI uri) {
        return "jdbc:postgresql://" + uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 5432) + uri.getRawPath()
            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }
}
