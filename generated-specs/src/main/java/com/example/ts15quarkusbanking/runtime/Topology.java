package com.example.ts15quarkusbanking.runtime;

/**
 * Event bus destinations: events are published to {@code exchange} (RabbitMQ topic exchange / Pub/Sub topic)
 * and consumed from {@code queue} (queue / pull subscription); dead-lettered messages go to {@code dlx}
 * and can be read from {@code dlq}.
 */
public record Topology(String exchange, String queue, String dlx, String dlq) {
    public static final Topology DEFAULT = new Topology("ts15-quarkus-banking.events", "ts15-quarkus-banking.consumer", "ts15-quarkus-banking.dlx", "ts15-quarkus-banking.dlq");

    public static Topology forPrefix(String prefix) {
        return new Topology(prefix + ".events", prefix + ".consumer", prefix + ".dlx", prefix + ".dlq");
    }
}
