package com.example.ts15quarkusbanking.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import java.sql.Connection;
import java.util.Map;

/**
 * Idempotent consumer: the message id is recorded in ghk_inbox in the same transaction as the
 * handler, so redeliveries are acknowledged without running the handler twice. A handler error
 * dead-letters the message (RabbitMQ DLX, Pub/Sub dead-letter topic): it is not redelivered.
 */
public class InboxConsumer {
    public record Meta(String messageId, String tenantId, String eventType, Connection connection) {}

    @FunctionalInterface
    public interface Handler {
        void handle(Map<String, Object> event, Meta meta) throws Exception;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ConnectionFactory connections;
    private final EventSubscription subscription;
    private final String consumerName;
    private final Handler handler;
    private final String s;

    public InboxConsumer(ConnectionFactory connections, EventSubscription subscription, String consumerName, Handler handler, String schema) {
        this.connections = connections;
        this.subscription = subscription;
        this.consumerName = consumerName;
        this.handler = handler;
        this.s = Schema.name(schema);
    }

    /** Processes messages until the source stays empty for idleMillis; returns how many were processed. */
    public int drain(long idleMillis) throws Exception {
        int processed = 0;
        long idleSince = System.currentTimeMillis();
        while (System.currentTimeMillis() - idleSince < idleMillis) {
            EventSubscription.Delivery delivery = subscription.poll();
            if (delivery == null) {
                Thread.sleep(50);
                continue;
            }
            process(delivery);
            processed++;
            idleSince = System.currentTimeMillis();
        }
        return processed;
    }

    @SuppressWarnings("unchecked")
    private void process(EventSubscription.Delivery delivery) throws Exception {
        BrokerMessage message = delivery.message();
        Span span = Telemetry.tracer().spanBuilder(subscription.source() + " process")
            .setParent(Telemetry.contextFrom(message.traceparent())).setSpanKind(SpanKind.CONSUMER)
            .setAttribute("messaging.system", subscription.system()).setAttribute("messaging.destination.name", subscription.source())
            .setAttribute("messaging.message.id", String.valueOf(message.messageId())).startSpan();
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                if (CommandService.update(conn, "INSERT INTO " + s + ".ghk_inbox (consumer, message_id) VALUES (?, ?) ON CONFLICT DO NOTHING", consumerName, message.messageId()) == 1) {
                    handler.handle(JSON.readValue(message.body(), Map.class), new Meta(message.messageId(), message.tenantId(), message.eventType(), conn));
                }
                conn.commit();
                delivery.ack().run();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            delivery.deadLetter().run();
        } finally {
            span.end();
        }
    }
}
