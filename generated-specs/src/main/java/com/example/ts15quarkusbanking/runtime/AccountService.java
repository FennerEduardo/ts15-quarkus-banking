package com.example.ts15quarkusbanking.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Accounts of the identity service: users are aggregates of type User in the same tables (the reads list them; the
 * password hash is a field of its own, never returned). Registration, sign-in and roles.
 */
public class AccountService {
    /** A user of the identity service: what the sign-in returns and the token carries. */
    public record Account(String id, String tenantId, String email, String name, List<String> roles, List<String> permissions) {}

    public static final class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    private static final int ITERATIONS = 120000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> NOT_PROFILE = Set.of("password", "password_confirmation", "roles", "permissions", "tenant");
    private final ConnectionFactory connections;
    private final String s;

    public AccountService(ConnectionFactory connections, String schema) {
        this.connections = connections;
        this.s = Schema.name(schema);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations, int bytes) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, bytes * 8)).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** pbkdf2_sha256$&lt;iterations&gt;$&lt;salt&gt;$&lt;hash&gt; (base64): the format every stack of gherkin-ai reads. */
    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2_sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(pbkdf2(password, salt, ITERATIONS, 32));
    }

    public static boolean verifyPassword(String password, Object stored) {
        String[] parts = String.valueOf(stored).split("\\$");
        if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) return false;
        try {
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] given = pbkdf2(password, Base64.getDecoder().decode(parts[2]), Integer.parseInt(parts[1]), expected.length);
            return MessageDigest.isEqual(given, expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static List<String> strings(Object v) {
        if (v instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        if (v instanceof String str && !str.isBlank()) return Arrays.stream(str.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        return List.of();
    }

    static Map<String, Object> parseData(String json) {
        try {
            return json == null || json.isBlank() ? new LinkedHashMap<>() : JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static Account account(String tenantId, String id, Map<String, Object> data) {
        return new Account(id, tenantId, String.valueOf(data.get("email")), data.get("name") == null ? null : String.valueOf(data.get("name")), strings(data.get("roles")), strings(data.get("permissions")));
    }

    private Optional<Map.Entry<String, Map<String, Object>>> find(String tenantId, String email) throws SQLException {
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("SELECT id, data FROM " + s + ".ghk_aggregates WHERE tenant_id = ? AND aggregate_type = 'User' AND NOT deleted AND lower(data::jsonb ->> 'email') = lower(?)")) {
            ps.setString(1, tenantId);
            ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(Map.entry(rs.getString(1), parseData(rs.getString(2)))) : Optional.empty();
            }
        }
    }

    /** Creates a user (ConflictException when the email is taken in the tenant). New users get GHK_DEFAULT_ROLES. */
    public Account register(String tenantId, Map<String, Object> input, List<String> roles) throws SQLException {
        String email = String.valueOf(input.getOrDefault("email", "")).trim();
        String password = String.valueOf(input.getOrDefault("password", ""));
        List<Contracts.FieldError> errors = new ArrayList<>();
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) errors.add(new Contracts.FieldError("email", "email", "email must be a valid email"));
        if (password.length() < 8) errors.add(new Contracts.FieldError("password", "min", "password must be at least 8"));
        if (!errors.isEmpty()) throw new Contracts.FieldValidationException(errors);
        if (find(tenantId, email).isPresent()) throw new ConflictException(email + " is already registered");
        List<String> granted = roles != null ? roles : strings(System.getenv().getOrDefault("GHK_DEFAULT_ROLES", ""));
        Map<String, Object> data = new LinkedHashMap<>();
        input.forEach((k, v) -> { if (!k.startsWith("_") && !NOT_PROFILE.contains(k)) data.put(k, v); });
        data.put("email", email);
        data.put("roles", granted);
        data.put("permissions", List.of());
        data.put("_password", hashPassword(password));
        String id = UUID.randomUUID().toString();
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                CommandService.update(conn, "INSERT INTO " + s + ".ghk_aggregates (tenant_id, aggregate_type, id, state, version, data) VALUES (?, 'User', ?, 'ACTIVE', 1, ?)", tenantId, id, JSON.writeValueAsString(data));
                CommandService.update(conn, "INSERT INTO " + s + ".ghk_outbox (id, tenant_id, aggregate_id, event_type, payload) VALUES (?, ?, ?, 'UserRegistered', ?)",
                    UUID.randomUUID().toString(), tenantId, id, JSON.writeValueAsString(Map.of("type", "UserRegistered", "aggregateId", id, "version", 1, "payload", Map.of("email", email, "roles", granted))));
                conn.commit();
            } catch (SQLException | RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                conn.rollback();
                if (e instanceof SQLException sql) throw sql;
                if (e instanceof RuntimeException re) throw re;
                throw new IllegalStateException(e);
            }
        }
        return account(tenantId, id, data);
    }

    /**
     * The account of valid credentials. A bcrypt hash imported from the monolith ($2y$, $2a$, $2b$) is accepted once
     * and replaced by the service's own PBKDF2 hash.
     */
    public Optional<Account> authenticate(String tenantId, String email, String password) throws SQLException {
        Optional<Map.Entry<String, Map<String, Object>>> user = find(tenantId, email);
        if (user.isEmpty()) return Optional.empty();
        String stored = String.valueOf(user.get().getValue().getOrDefault("_password", ""));
        if (stored.matches("^\\$2[aby]\\$.*")) {
            boolean ok;
            try {
                ok = org.mindrot.jbcrypt.BCrypt.checkpw(password, "$2a$" + stored.substring(4));
            } catch (IllegalArgumentException e) {
                ok = false;
            }
            if (!ok) return Optional.empty();
            try (Connection conn = connections.open()) {
                CommandService.update(conn, "UPDATE " + s + ".ghk_aggregates SET data = jsonb_set(data::jsonb, '{_password}', to_jsonb(?::text))::text WHERE tenant_id = ? AND aggregate_type = 'User' AND id = ?", hashPassword(password), tenantId, user.get().getKey());
            }
            return Optional.of(account(tenantId, user.get().getKey(), user.get().getValue()));
        }
        return verifyPassword(password, stored) ? Optional.of(account(tenantId, user.get().getKey(), user.get().getValue())) : Optional.empty();
    }

    /** What an import did: how many users it created and the emails it skipped (already registered, or no hash). */
    public record ImportResult(int imported, List<String> skipped) {}

    /**
     * The monolith's users with the hash they have (password_hash: bcrypt or PBKDF2) or a password, their roles and their
     * old id (kept as legacy_id). An email already registered in the tenant is skipped.
     */
    public ImportResult importUsers(String tenantId, List<Map<String, Object>> users) throws SQLException {
        int imported = 0;
        List<String> skipped = new ArrayList<>();
        for (Map<String, Object> u : users) {
            String email = String.valueOf(u.getOrDefault("email", "")).trim();
            Object given = u.get("password_hash") != null ? u.get("password_hash") : u.get("passwordHash");
            String hash = given != null ? String.valueOf(given) : u.get("password") != null ? hashPassword(String.valueOf(u.get("password"))) : "";
            if (email.isEmpty() || hash.isEmpty() || find(tenantId, email).isPresent()) { skipped.add(email.isEmpty() ? "(no email)" : email); continue; }
            Map<String, Object> data = new LinkedHashMap<>();
            u.forEach((k, v) -> { if (!k.startsWith("_") && !Set.of("id", "password", "password_hash", "passwordHash", "roles", "permissions").contains(k)) data.put(k, v); });
            data.put("email", email);
            data.put("roles", strings(u.get("roles")));
            data.put("permissions", strings(u.get("permissions")));
            if (u.get("id") != null) data.put("legacy_id", String.valueOf(u.get("id")));
            data.put("_password", hash);
            try (Connection conn = connections.open()) {
                CommandService.update(conn, "INSERT INTO " + s + ".ghk_aggregates (tenant_id, aggregate_type, id, state, version, data) VALUES (?, 'User', ?, 'ACTIVE', 1, ?)", tenantId, UUID.randomUUID().toString(), JSON.writeValueAsString(data));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            imported++;
        }
        return new ImportResult(imported, skipped);
    }

    /** Replaces a user's roles and permissions (empty when the user does not exist in the tenant). */
    public Optional<Account> grant(String tenantId, String userId, List<String> roles, List<String> permissions) throws SQLException {
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("UPDATE " + s + ".ghk_aggregates SET data = (data::jsonb || jsonb_build_object('roles', ?::jsonb, 'permissions', ?::jsonb))::text, version = version + 1, updated_at = now() WHERE tenant_id = ? AND aggregate_type = 'User' AND id = ? AND NOT deleted RETURNING id, data")) {
            ps.setString(1, JSON.writeValueAsString(roles));
            ps.setString(2, JSON.writeValueAsString(permissions));
            ps.setString(3, tenantId);
            ps.setString(4, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(account(tenantId, rs.getString(1), parseData(rs.getString(2)))) : Optional.empty();
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** GHK_ADMIN_EMAIL / GHK_ADMIN_PASSWORD: the first administrator, with GHK_ADMIN_ROLES (default admin). Idempotent. */
    public void bootstrapAdmin() throws SQLException {
        String email = System.getenv("GHK_ADMIN_EMAIL");
        String password = System.getenv("GHK_ADMIN_PASSWORD");
        if (email == null || email.isBlank() || password == null || password.isBlank()) return;
        String tenant = System.getenv().getOrDefault("GHK_DEFAULT_TENANT", "default");
        List<String> roles = strings(System.getenv().getOrDefault("GHK_ADMIN_ROLES", "admin"));
        Optional<Map.Entry<String, Map<String, Object>>> existing = find(tenant, email);
        if (existing.isPresent()) {
            Set<String> merged = new LinkedHashSet<>(strings(existing.get().getValue().get("roles")));
            merged.addAll(roles);
            grant(tenant, existing.get().getKey(), new ArrayList<>(merged), strings(existing.get().getValue().get("permissions")));
            return;
        }
        register(tenant, Map.of("email", email, "password", password, "name", "Administrator"), roles);
    }
}
