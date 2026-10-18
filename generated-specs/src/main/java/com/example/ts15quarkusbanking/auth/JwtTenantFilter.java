package com.example.ts15quarkusbanking.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Authenticates /api requests with an HS256 bearer token (JWT_SECRET) and exposes the tenant from the
 * "tenant_id" claim through TenantContext. The tenant is never read from a header such as X-Tenant-Id,
 * and there is no default tenant: requests without one get 401.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class JwtTenantFilter implements ContainerRequestFilter {
    public static final String TENANT_CLAIM = "tenant_id";
    private static final ObjectMapper JSON = new ObjectMapper();

    @ConfigProperty(name = "ghk.jwt-secret")
    Optional<String> secret;

    @Inject
    TenantContext tenant;

    @Override
    public void filter(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        if (!(path.startsWith("/api/") || path.startsWith("api/"))) return;
        String tenantId = tenantOf(request.getHeaderString("Authorization"), secret.orElse(""));
        if (tenantId == null) {
            request.abortWith(Response.status(Response.Status.UNAUTHORIZED).type(MediaType.APPLICATION_JSON)
                .entity("{\"detail\":\"A valid bearer token with a tenant is required\"}").build());
            return;
        }
        tenant.set(tenantId);
    }

    /** Tenant claim of a valid HS256 token (signature, exp, nbf checked), else null. */
    public static String tenantOf(String authorization, String secret) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7) || secret == null || secret.isEmpty()) return null;
        String[] parts = authorization.substring(7).trim().split("\\.");
        if (parts.length != 3) return null;
        try {
            Map<?, ?> header = JSON.readValue(Base64.getUrlDecoder().decode(parts[0]), Map.class);
            if (!"HS256".equals(header.get("alg"))) return null;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) return null;
            Map<?, ?> claims = JSON.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
            long now = System.currentTimeMillis() / 1000;
            if (claims.get("exp") instanceof Number exp && now >= exp.longValue()) return null;
            if (claims.get("nbf") instanceof Number nbf && now < nbf.longValue()) return null;
            return claims.get(TENANT_CLAIM) instanceof String t && !t.isBlank() ? t : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Signs an HS256 token (tests and local tooling; production tokens come from your identity provider). */
    public static String sign(Map<String, Object> claims, String secret) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String head = enc.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String body = enc.encodeToString(JSON.writeValueAsBytes(claims));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return head + "." + body + "." + enc.encodeToString(mac.doFinal((head + "." + body).getBytes(StandardCharsets.UTF_8)));
    }
}
