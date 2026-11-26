package com.example.ts15quarkusbanking.runtime;

import com.example.ts15quarkusbanking.domain.PaymentExecutionAggregate;
import com.example.ts15quarkusbanking.domain.PaymentExecutionCommand;
import com.example.ts15quarkusbanking.domain.PaymentExecutionDomainEvent;
import com.example.ts15quarkusbanking.domain.PaymentExecutionState;
import com.example.ts15quarkusbanking.domain.DomainValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes a command in one transaction: idempotency claim, aggregate load (FOR UPDATE), domain
 * logic, aggregate save and outbox insert. A domain error rolls back everything.
 */
public class CommandService {
    /** Aggregate type used when a request does not name one. */
    public static final String DEFAULT_AGGREGATE = "PaymentExecution";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    /**
     * aggregateType: the feature's aggregate (e.g. "PaymentExecution"); null means DEFAULT_AGGREGATE. caller: the roles and
     * permissions of the token, checked against the command's contract when given.
     */
    public record Request(String tenantId, String aggregateId, String command, Map<String, Object> payload, String idempotencyKey, String traceparent, String aggregateType, Contracts.Caller caller) {
        public Request(String tenantId, String aggregateId, String command, Map<String, Object> payload, String idempotencyKey, String traceparent, String aggregateType) {
            this(tenantId, aggregateId, command, payload, idempotencyKey, traceparent, aggregateType, null);
        }

        public Request(String tenantId, String aggregateId, String command, Map<String, Object> payload, String idempotencyKey, String traceparent) {
            this(tenantId, aggregateId, command, payload, idempotencyKey, traceparent, null, null);
        }

        public Request(String tenantId, String aggregateId, String command, Map<String, Object> payload) {
            this(tenantId, aggregateId, command, payload, null, null, null, null);
        }

        public Request(String tenantId, String aggregateId, String command) {
            this(tenantId, aggregateId, command, Map.of(), null, null, null, null);
        }

        String type() {
            return aggregateType == null || aggregateType.isBlank() ? DEFAULT_AGGREGATE : aggregateType;
        }
    }

    /** The result of running a command on an aggregate: its new state and the recorded event. */
    private record Applied(String state, long version, String eventType, long eventVersion, Object event) {}

    public record Result(String status, String aggregateId, String eventType, Long version) {}

    public record Snapshot(String state, long version) {}

    /** A page of the reads: each item has id, state, version and the fields its commands wrote. */
    public record ListResult(List<Map<String, Object>> items, int page, int size, long total) {}

    private static final java.util.regex.Pattern FIELD = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final ConnectionFactory connections;
    private final String s;

    public CommandService(ConnectionFactory connections, String schema) {
        this.connections = connections;
        this.s = Schema.name(schema);
    }

    public Result handle(Request request) throws SQLException {
        if (request.tenantId() == null || request.tenantId().isBlank()) throw new DomainValidationException("tenantId is required");
        Contracts.OperationContract op = Contracts.command(request.type(), request.command());
        Contracts.authorize(op, request.caller(), request.type() + "." + request.command());
        // Fields starting with _ belong to the service (the identity's password hash): a command never writes them.
        Map<String, Object> payload = new LinkedHashMap<>();
        if (request.payload() != null) request.payload().forEach((k, v) -> { if (!k.startsWith("_")) payload.put(k, v); });
        Contracts.validate(op, payload);
        Request req = new Request(request.tenantId(), request.aggregateId(), request.command(), payload, request.idempotencyKey(), request.traceparent(), request.aggregateType(), request.caller());
        Context parent = Telemetry.contextFrom(req.traceparent());
        Span span = Telemetry.tracer().spanBuilder("PaymentExecution." + req.command()).setParent(parent).setSpanKind(SpanKind.INTERNAL)
            .setAttribute("tenant.id", req.tenantId()).setAttribute("aggregate.id", req.aggregateId()).startSpan();
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                Result result = execute(conn, req, Telemetry.traceparentOf(parent, span));
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException | RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    private Result execute(Connection conn, Request req, String traceparent) throws SQLException {
        String key = req.idempotencyKey();
        if (key != null) {
            if (update(conn, "INSERT INTO " + s + ".ghk_idempotency (tenant_id, key, status) VALUES (?, ?, 'PROCESSING') ON CONFLICT DO NOTHING", req.tenantId(), key) == 0) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT status, response FROM " + s + ".ghk_idempotency WHERE tenant_id = ? AND key = ?")) {
                    ps.setString(1, req.tenantId());
                    ps.setString(2, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && "COMPLETED".equals(rs.getString(1))) {
                            Result stored = readJson(rs.getString(2));
                            return new Result("replayed", stored.aggregateId(), stored.eventType(), stored.version());
                        }
                    }
                }
                return new Result("in-progress", req.aggregateId(), null, null);
            }
        }
        String type = req.type();
        String state = null;
        long version = 0;
        String dataJson = "{}";
        try (PreparedStatement ps = conn.prepareStatement("SELECT state, version, data FROM " + s + ".ghk_aggregates WHERE tenant_id = ? AND aggregate_type = ? AND id = ? FOR UPDATE")) {
            ps.setString(1, req.tenantId());
            ps.setString(2, type);
            ps.setString(3, req.aggregateId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    state = rs.getString(1);
                    version = rs.getLong(2);
                    dataJson = rs.getString(3);
                }
            }
        }
        // Secrets (a password, a reset token) are validated, never stored: not in the read model, not in the event.
        Map<String, Object> payload = new LinkedHashMap<>();
        if (req.payload() != null) req.payload().forEach((k, v) -> { if (!Contracts.SECRET.matcher(k).matches()) payload.put(k, v); });
        Applied applied = switch (type) {
            case "PaymentExecution" -> applyPaymentExecution(req.aggregateId(), state, version, req.command(), payload);
            default -> throw new DomainValidationException("Unknown aggregate type " + type);
        };
        // The read model: the fields the commands wrote, the last value of each.
        Map<String, Object> data = AccountService.parseData(dataJson);
        data.putAll(payload);
        Contracts.OperationContract op = Contracts.command(type, req.command());
        update(conn, "INSERT INTO " + s + ".ghk_aggregates (tenant_id, aggregate_type, id, state, version, data, deleted) VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (tenant_id, aggregate_type, id) DO UPDATE SET state = EXCLUDED.state, version = EXCLUDED.version, data = EXCLUDED.data, deleted = EXCLUDED.deleted, updated_at = now()",
            req.tenantId(), type, req.aggregateId(), applied.state(), applied.version(), writeJson(data), op != null && op.deletes());
        // A command of a role aggregate (assign_docente) gives its role to the user it names, or to the caller.
        Contracts.AggregateContract grantsOf = Contracts.ALL.get(type);
        String role = grantsOf == null ? null : grantsOf.grants().get(req.command());
        Object user = payload.containsKey("user_id") ? payload.get("user_id") : req.caller() == null ? null : req.caller().subject();
        if (role != null && user != null) update(conn, "UPDATE __SCHEMA__.ghk_aggregates SET data = jsonb_set(data::jsonb, '{roles}', (SELECT COALESCE(jsonb_agg(DISTINCT r), '[]'::jsonb) FROM jsonb_array_elements_text(COALESCE(data::jsonb -> 'roles', '[]'::jsonb) || jsonb_build_array(?::text)) AS r))::text, version = version + 1, updated_at = now() WHERE tenant_id = ? AND aggregate_type = 'User' AND NOT deleted AND (id = ? OR data::jsonb ->> 'legacy_id' = ?)".replace("__SCHEMA__", s), role, req.tenantId(), String.valueOf(user), String.valueOf(user));
        update(conn, "INSERT INTO " + s + ".ghk_outbox (id, tenant_id, aggregate_id, event_type, payload, traceparent) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), req.tenantId(), req.aggregateId(), applied.eventType(), writeJson(applied.event()), traceparent);
        Result result = new Result("created", req.aggregateId(), applied.eventType(), applied.eventVersion());
        if (key != null) {
            update(conn, "UPDATE " + s + ".ghk_idempotency SET status = 'COMPLETED', response = ? WHERE tenant_id = ? AND key = ?", writeJson(result), req.tenantId(), key);
        }
        return result;
    }

    private static Applied applyPaymentExecution(String id, String state, long version, String command, Map<String, Object> payload) {
        PaymentExecutionAggregate aggregate = state == null ? new PaymentExecutionAggregate(id) : PaymentExecutionAggregate.restore(id, PaymentExecutionState.valueOf(state), version);
        PaymentExecutionCommand cmd = new PaymentExecutionCommand(id, payload);
        PaymentExecutionDomainEvent event = switch (command) {
            case "execute_payment" -> aggregate.executePayment(cmd);
            default -> throw new DomainValidationException("Unknown command " + command);
        };
        return new Applied(aggregate.getState().name(), aggregate.getVersion(), event.type().name(), event.version(), event);
    }

    /** The aggregate as tenantId sees it (empty for other tenants' aggregates). */
    public Optional<Snapshot> load(String tenantId, String aggregateId) throws SQLException {
        return load(tenantId, aggregateId, DEFAULT_AGGREGATE);
    }

    public Optional<Snapshot> load(String tenantId, String aggregateId, String aggregateType) throws SQLException {
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("SELECT state, version FROM " + s + ".ghk_aggregates WHERE tenant_id = ? AND aggregate_type = ? AND id = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, aggregateType);
            ps.setString(3, aggregateId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Snapshot(rs.getString(1), rs.getLong(2))) : Optional.empty();
            }
        }
    }

    /** An aggregate as the reads return it: the fields its commands wrote (none starting with _), id, state and version. */
    private static Map<String, Object> item(String id, String state, long version, String data) {
        Map<String, Object> item = new LinkedHashMap<>();
        AccountService.parseData(data).forEach((k, v) -> { if (!k.startsWith("_")) item.put(k, v); });
        item.put("id", id);
        item.put("state", state);
        item.put("version", version);
        return item;
    }

    public ListResult list(String tenantId, String aggregateType, Map<String, String> filters, int page, int size) throws SQLException {
        return list(tenantId, aggregateType, filters, page, size, null);
    }

    /**
     * The tenant's aggregates of a type that no command deleted, newest first. Filters keep those whose field equals
     * the value (?editorial_id=7: the ListXByEditorial reads of the features).
     */
    public ListResult list(String tenantId, String aggregateType, Map<String, String> filters, int page, int size, Contracts.Caller caller) throws SQLException {
        Contracts.AggregateContract contract = Contracts.ALL.get(aggregateType);
        if (contract != null) Contracts.authorize(contract.list(), caller, aggregateType + ".list");
        int p = Math.max(1, page);
        int n = Math.min(200, Math.max(1, size));
        StringBuilder where = new StringBuilder("tenant_id = ? AND aggregate_type = ? AND NOT deleted");
        List<Object> args = new java.util.ArrayList<>(List.of(tenantId, aggregateType));
        if (filters != null) filters.forEach((k, v) -> {
            if (!FIELD.matcher(k).matches() || k.startsWith("_")) return;
            where.append(" AND (data::jsonb ->> ?) = ?");
            args.add(k);
            args.add(v);
        });
        String from = " FROM " + s + ".ghk_aggregates WHERE " + where;
        try (Connection conn = connections.open()) {
            long total;
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*)" + from)) {
                for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, state, version, data" + from + " ORDER BY updated_at DESC, id LIMIT " + n + " OFFSET " + ((p - 1) * n))) {
                for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) items.add(item(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)));
                }
            }
            return new ListResult(items, p, n, total);
        }
    }

    public Optional<Map<String, Object>> get(String tenantId, String aggregateType, String aggregateId) throws SQLException {
        return get(tenantId, aggregateType, aggregateId, null);
    }

    /** One aggregate of the tenant (empty when it does not exist, belongs to another tenant or was deleted). */
    public Optional<Map<String, Object>> get(String tenantId, String aggregateType, String aggregateId, Contracts.Caller caller) throws SQLException {
        Contracts.AggregateContract contract = Contracts.ALL.get(aggregateType);
        if (contract != null) Contracts.authorize(contract.get(), caller, aggregateType + ".get");
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("SELECT id, state, version, data FROM " + s + ".ghk_aggregates WHERE tenant_id = ? AND aggregate_type = ? AND id = ? AND NOT deleted")) {
            ps.setString(1, tenantId);
            ps.setString(2, aggregateType);
            ps.setString(3, aggregateId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(item(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4))) : Optional.empty();
            }
        }
    }

    static int update(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps.executeUpdate();
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Result readJson(String value) {
        try {
            return JSON.readValue(value, Result.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
