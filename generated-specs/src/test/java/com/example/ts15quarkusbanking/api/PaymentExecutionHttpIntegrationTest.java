package com.example.ts15quarkusbanking.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ts15quarkusbanking.auth.JwtTenantFilter;
import com.example.ts15quarkusbanking.runtime.ConnectionFactory;
import com.example.ts15quarkusbanking.runtime.Schema;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** HTTP contract of the application layer against PostgreSQL (needs DATABASE_URL). */
@Tag("integration")
@QuarkusTest
class PaymentExecutionHttpIntegrationTest {
    static final String SECRET = "integration-test-secret-with-32-chars!";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TestHTTPResource("/")
    URL base;

    @BeforeAll
    static void migrate() throws Exception {
        Schema.migrate(ConnectionFactory.fromUrl(System.getenv("DATABASE_URL")), "public");
    }

    private HttpResponse<String> post(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(base.toURI().resolve(path.substring(1)))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        headers.forEach(req::header);
        return HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String bearer(Map<String, Object> claims) throws Exception {
        return "Bearer " + JwtTenantFilter.sign(claims, SECRET);
    }

    @Test
    void rejectsRequestsWithoutAValidTokenAndNeverTrustsXTenantId() throws Exception {
        String path = "/api/v1/payment-execution/" + UUID.randomUUID() + "/execute_payment";
        assertEquals(401, post(path, Map.of()).statusCode());
        assertEquals(401, post(path, Map.of("X-Tenant-Id", "acme")).statusCode());
        assertEquals(401, post(path, Map.of("Authorization", bearer(Map.of("sub", "u1")))).statusCode());
        assertEquals(401, post(path, Map.of("Authorization", "Bearer " + JwtTenantFilter.sign(Map.of(JwtTenantFilter.TENANT_CLAIM, "acme"), "wrong-secret"))).statusCode());
    }

    @Test
    void runsACommandIntoTheOutboxForTheTokenTenantIdempotently() throws Exception {
        String tenant = "tenant-" + UUID.randomUUID();
        String id = UUID.randomUUID().toString();
        Map<String, String> headers = Map.of("Authorization", bearer(Map.of(JwtTenantFilter.TENANT_CLAIM, tenant)), "Idempotency-Key", UUID.randomUUID().toString(), "X-Tenant-Id", "someone-else");
        String path = "/api/v1/payment-execution/" + id + "/execute_payment";
        assertEquals(202, post(path, headers).statusCode());
        HttpResponse<String> replay = post(path, headers);
        assertEquals(200, replay.statusCode());
        assertTrue(replay.body().contains("replayed"));
        try (Connection conn = ConnectionFactory.fromUrl(System.getenv("DATABASE_URL")).open();
             PreparedStatement ps = conn.prepareStatement("SELECT tenant_id FROM ghk_outbox WHERE aggregate_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(tenant, rs.getString(1));
            }
        }
    }

    @Test
    void mapsDomainValidationErrorsTo422() throws Exception {
        String path = "/api/v1/payment-execution/" + UUID.randomUUID() + "/no_such_command";
        assertEquals(422, post(path, Map.of("Authorization", bearer(Map.of(JwtTenantFilter.TENANT_CLAIM, "acme")))).statusCode());
    }

    @Test
    void reportsHealth() throws Exception {
        HttpResponse<String> live = HTTP.send(HttpRequest.newBuilder(base.toURI().resolve("q/health/live")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, live.statusCode());
        HttpResponse<String> ready = HTTP.send(HttpRequest.newBuilder(base.toURI().resolve("q/health/ready")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ready.statusCode());
    }
}
