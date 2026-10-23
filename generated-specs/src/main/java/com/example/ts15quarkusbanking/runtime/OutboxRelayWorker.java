package com.example.ts15quarkusbanking.runtime;

import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Publishes the outbox every 1000 ms when a broker is configured (Broker: GHK_BROKER, AMQP_URL, PUBSUB_*). */
@ApplicationScoped
public class OutboxRelayWorker {
    private static final Logger LOG = Logger.getLogger(OutboxRelayWorker.class);
    private final String workerId = "payment-execution-" + UUID.randomUUID();
    private Broker broker;
    private EventPublisher publisher;
    private OutboxRelay relay;
    private boolean warned;

    @Inject
    ConnectionFactory connections;

    @ConfigProperty(name = "ghk.outbox.relay-enabled", defaultValue = "true")
    boolean enabled;

    @Scheduled(every = "PT1S", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    synchronized void publish() {
        if (!enabled) return;
        if (!Broker.configured()) {
            if (!warned) LOG.warn("No event broker is configured (AMQP_URL, or GHK_BROKER=pubsub with PUBSUB_PROJECT_ID): outbox events are stored but not published.");
            warned = true;
            return;
        }
        try {
            if (relay == null) {
                broker = Broker.fromEnv();
                // GHK_DECLARE_TOPOLOGY=false when infrastructure as code owns the topology (least-privilege credentials).
                if (!"false".equals(System.getenv("GHK_DECLARE_TOPOLOGY"))) broker.declare(Topology.DEFAULT);
                publisher = broker.publisher(Topology.DEFAULT.exchange());
                relay = new OutboxRelay(connections, publisher, "public");
            }
            relay.publishBatch(workerId, 50);
        } catch (Exception e) {
            LOG.errorf("Outbox relay failed: %s", e.getMessage());
            close();
        }
    }

    private void close() {
        relay = null;
        try { if (publisher != null) publisher.close(); } catch (Exception ignored) { }
        try { if (broker != null) broker.close(); } catch (Exception ignored) { }
        publisher = null;
        broker = null;
    }

    @PreDestroy
    synchronized void destroy() {
        close();
    }
}
