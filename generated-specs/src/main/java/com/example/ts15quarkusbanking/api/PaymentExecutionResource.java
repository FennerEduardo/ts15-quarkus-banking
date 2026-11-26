package com.example.ts15quarkusbanking.api;

import com.example.ts15quarkusbanking.auth.TenantContext;
import com.example.ts15quarkusbanking.runtime.CommandService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/** POST /api/v1/payment-execution/{id}/{command} (execute_payment); GET /api/v1/payment-execution[/{id}]. Runs on a worker thread (blocking JDBC). */
// ghk:implements PaymentExecution
@Path("/api/v1/payment-execution")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PaymentExecutionResource {
    private static final Map<String, Integer> STATUS = Map.of("created", 202, "replayed", 200, "in-progress", 409);

    @Inject
    CommandService commands;

    @Inject
    TenantContext tenant;

    @POST
    @Path("/{id}/{command}")
    public Response handle(
        @PathParam("id") String id,
        @PathParam("command") String command,
        @HeaderParam("Idempotency-Key") String idempotencyKey,
        @HeaderParam("traceparent") String traceparent,
        Map<String, Object> payload
    ) throws SQLException {
        CommandService.Result result = commands.handle(new CommandService.Request(tenant.tenantId(), id, command, payload == null ? Map.of() : payload, idempotencyKey, traceparent, "PaymentExecution", tenant.caller()));
        return Response.status(STATUS.getOrDefault(result.status(), 200)).entity(result).build();
    }

    /** The tenant's Ejecución de pagos entre cuentas (BIAN Payment Execution) aggregates (?field=value&page=&size=: the List queries of the feature). */
    @GET
    public Object list(@Context UriInfo uri) throws SQLException {
        if (tenant.anonymous() && !false) return UNAUTHORIZED.get();
        Map<String, String> filters = new HashMap<>();
        uri.getQueryParameters().forEach((k, v) -> { if (!k.equals("page") && !k.equals("size") && !v.isEmpty()) filters.put(k, v.get(0)); });
        int page = parse(uri.getQueryParameters().getFirst("page"), 1);
        int size = parse(uri.getQueryParameters().getFirst("size"), 20);
        return commands.list(tenant.tenantId(), "PaymentExecution", filters, page, size, tenant.caller());
    }

    /** One Ejecución de pagos entre cuentas (BIAN Payment Execution) (the Get query of the feature). */
    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) throws SQLException {
        if (tenant.anonymous() && !false) return UNAUTHORIZED.get();
        return commands.get(tenant.tenantId(), "PaymentExecution", id, tenant.caller())
            .map(item -> Response.ok(item).build())
            .orElseGet(() -> Response.status(404).entity(Map.of("detail", "PaymentExecution " + id + " was not found")).build());
    }

    private static final java.util.function.Supplier<Response> UNAUTHORIZED = () -> Response.status(401).entity(Map.of("detail", "A valid bearer token with a tenant is required")).build();

    private static int parse(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
