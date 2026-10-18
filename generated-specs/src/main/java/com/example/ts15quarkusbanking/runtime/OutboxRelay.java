package com.example.ts15quarkusbanking.runtime;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Publishes pending outbox rows. Rows are claimed with FOR UPDATE SKIP LOCKED and a lease, so
 * concurrent relays never publish the same row; the publisher returns only once the broker has accepted
 * the message (RabbitMQ confirms, Pub/Sub publish response), before a row is marked published.
 * Use one relay (and publisher) per thread.
 */
public class OutboxRelay {
    private final ConnectionFactory connections;
    private final EventPublisher publisher;
    private final String s;
    private final int leaseSeconds;
    private final int maxAttempts;

    public OutboxRelay(ConnectionFactory connections, EventPublisher publisher, String schema) {
        this.connections = connections;
        this.publisher = publisher;
        this.s = Schema.name(schema);
        this.leaseSeconds = 30;
        this.maxAttempts = 5;
    }

    private record Claimed(String id, String tenantId, String eventType, String payload, String traceparent) {}

    public int publishBatch(String workerId, int limit) throws Exception {
        List<Claimed> batch = new ArrayList<>();
        try (Connection conn = connections.open();
             PreparedStatement ps = conn.prepareStatement("UPDATE " + s + ".ghk_outbox SET claimed_by = ?, claimed_until = now() + make_interval(secs => ?), attempts = attempts + 1 "
                 + "WHERE id IN (SELECT id FROM " + s + ".ghk_outbox WHERE published_at IS NULL AND failed_at IS NULL AND (claimed_until IS NULL OR claimed_until < now()) "
                 + "ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING id, tenant_id, event_type, payload, traceparent")) {
            ps.setString(1, workerId);
            ps.setInt(2, leaseSeconds);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) batch.add(new Claimed(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            }
        }
        int published = 0;
        try (Connection conn = connections.open()) {
            for (Claimed row : batch) {
                Context parent = Telemetry.contextFrom(row.traceparent());
                Span span = Telemetry.tracer().spanBuilder(publisher.destination() + " publish").setParent(parent).setSpanKind(SpanKind.PRODUCER)
                    .setAttribute("messaging.system", publisher.system()).setAttribute("messaging.destination.name", publisher.destination())
                    .setAttribute("messaging.message.id", row.id()).setAttribute("tenant.id", row.tenantId()).startSpan();
                try {
                    String traceparent = Telemetry.traceparentOf(parent, span);
                    publisher.publish(new BrokerMessage(row.id(), row.eventType(), row.tenantId(), traceparent, row.payload().getBytes(StandardCharsets.UTF_8)));
                    CommandService.update(conn, "UPDATE " + s + ".ghk_outbox SET published_at = now(), claimed_until = NULL WHERE id = ? AND claimed_by = ?", row.id(), workerId);
                    published++;
                } catch (Exception e) {
                    span.recordException(e);
                    span.setStatus(StatusCode.ERROR);
                    CommandService.update(conn, "UPDATE " + s + ".ghk_outbox SET claimed_until = NULL, last_error = ?, failed_at = CASE WHEN attempts >= ? THEN now() ELSE NULL END WHERE id = ?",
                        String.valueOf(e.getMessage()), maxAttempts, row.id());
                } finally {
                    span.end();
                }
            }
        }
        return published;
    }
}
