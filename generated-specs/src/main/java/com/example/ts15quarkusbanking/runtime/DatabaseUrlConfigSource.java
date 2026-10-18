package com.example.ts15quarkusbanking.runtime;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Maps DATABASE_URL (libpq form, postgres://user:password@host:5432/db, shared by every gherkin-ai stack)
 * to the Quarkus datasource (quarkus.datasource.jdbc.url / username / password). Explicit
 * QUARKUS_DATASOURCE_* environment variables still win (ordinal below the environment).
 */
public class DatabaseUrlConfigSource implements ConfigSource {
    private final Map<String, String> values = new HashMap<>();

    public DatabaseUrlConfigSource() {
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isBlank()) return;
        URI uri = URI.create(url.replaceFirst("^postgres(ql)?://", "postgresql://"));
        values.put("quarkus.datasource.jdbc.url", "jdbc:postgresql://" + uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 5432) + uri.getPath());
        String[] userInfo = uri.getRawUserInfo() == null ? new String[0] : uri.getRawUserInfo().split(":", 2);
        if (userInfo.length > 0) values.put("quarkus.datasource.username", URLDecoder.decode(userInfo[0], StandardCharsets.UTF_8));
        if (userInfo.length > 1) values.put("quarkus.datasource.password", URLDecoder.decode(userInfo[1], StandardCharsets.UTF_8));
    }

    @Override
    public Set<String> getPropertyNames() {
        return values.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return values.get(propertyName);
    }

    @Override
    public String getName() {
        return "DATABASE_URL";
    }

    @Override
    public int getOrdinal() {
        return 275;
    }
}
