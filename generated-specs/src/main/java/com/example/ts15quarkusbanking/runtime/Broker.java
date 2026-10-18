package com.example.ts15quarkusbanking.runtime;

/**
 * The event broker behind the outbox relay and the inbox consumer, chosen at run time so one image runs on
 * any cloud: GHK_BROKER=rabbitmq (AMQP_URL) or GHK_BROKER=pubsub (PUBSUB_PROJECT_ID or GOOGLE_CLOUD_PROJECT;
 * PUBSUB_EMULATOR_HOST for the emulator). Without GHK_BROKER the configured variables decide.
 */
public interface Broker extends AutoCloseable {
    /** Broker of the generated configuration (stack.messaging). */
    String DEFAULT_KIND = "pubsub";

    String system();

    /** Creates the topology if missing (idempotent); in production it usually comes from infrastructure as code. */
    void declare(Topology topology) throws Exception;

    EventPublisher publisher(String destination) throws Exception;

    /** @param topology where dead-lettered messages go */
    EventSubscription subscribe(String source, Topology topology) throws Exception;

    /** False when topics and subscriptions cannot be created at run time (Service Bus emulator: fixed Config.json). */
    default boolean dynamicTopology() {
        return true;
    }

    static String kind() {
        String explicit = System.getenv("GHK_BROKER");
        if (explicit != null && !explicit.isBlank()) return explicit.trim().toLowerCase();
        if (System.getenv("AMQP_URL") != null) return "rabbitmq";
        if (System.getenv("PUBSUB_PROJECT_ID") != null || System.getenv("PUBSUB_EMULATOR_HOST") != null) return "pubsub";
        if (System.getenv("SERVICEBUS_CONNECTION_STRING") != null || System.getenv("SERVICEBUS_NAMESPACE") != null) return "servicebus";
        return DEFAULT_KIND;
    }

    /** Whether the variables of the selected broker are set. */
    static boolean configured() {
        return switch (kind()) {
            case "pubsub" -> System.getenv("PUBSUB_PROJECT_ID") != null || System.getenv("GOOGLE_CLOUD_PROJECT") != null;
            case "servicebus" -> System.getenv("SERVICEBUS_CONNECTION_STRING") != null || System.getenv("SERVICEBUS_NAMESPACE") != null;
            default -> System.getenv("AMQP_URL") != null && !System.getenv("AMQP_URL").isBlank();
        };
    }

    static Broker fromEnv() throws Exception {
        return switch (kind()) {
            case "pubsub" -> PubSubBroker.fromEnv();
            case "servicebus" -> throw new IllegalStateException("The Azure Service Bus adapter is not generated: set stack.messaging to \"servicebus\" and regenerate");
            case "rabbitmq" -> {
                String url = System.getenv("AMQP_URL");
                if (url == null || url.isBlank()) throw new IllegalStateException("AMQP_URL is not set (amqp://user:password@host:5672)");
                yield RabbitMqBroker.connect(url);
            }
            default -> throw new IllegalStateException("Unsupported GHK_BROKER: " + kind() + " (rabbitmq, pubsub, servicebus)");
        };
    }
}
