package com.example.ts15quarkusbanking.api;

import com.example.ts15quarkusbanking.auth.TenantContext;
import com.example.ts15quarkusbanking.runtime.CommandService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.SQLException;
import java.util.Map;

/** POST /api/v1/payment-execution/{id}/{command} (process_payment_execution). Runs on a worker thread (blocking JDBC). */
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
        CommandService.Result result = commands.handle(new CommandService.Request(tenant.tenantId(), id, command, payload == null ? Map.of() : payload, idempotencyKey, traceparent, "PaymentExecution"));
        return Response.status(STATUS.getOrDefault(result.status(), 200)).entity(result).build();
    }
}
