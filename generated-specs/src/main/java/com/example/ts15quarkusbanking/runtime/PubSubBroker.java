package com.example.ts15quarkusbanking.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Cloud Pub/Sub over its REST API (no client library): topics for the exchange and the DLX, pull
 * subscriptions for the queue and the DLQ. publish returns once Pub/Sub has stored the message. A failed
 * message is dead-lettered by the consumer: published to the DLX topic, then acknowledged.
 * Auth: none against the emulator (PUBSUB_EMULATOR_HOST); otherwise GOOGLE_OAUTH_ACCESS_TOKEN or the
 * metadata-server token of the workload's service account (Cloud Run, GKE workload identity).
 */
public final class PubSubBroker implements Broker {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String METADATA_TOKEN = "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String project;
    private final String root;
    private final boolean emulator;
    private String token;
    private long tokenExpiresAt;

    public PubSubBroker(String project, String emulatorHost) {
        this.project = project;
        this.emulator = emulatorHost != null && !emulatorHost.isBlank();
        this.root = (emulator ? "http://" + emulatorHost : "https://pubsub.googleapis.com") + "/v1/projects/" + project;
    }

    public static PubSubBroker fromEnv() {
        String project = System.getenv("PUBSUB_PROJECT_ID") != null ? System.getenv("PUBSUB_PROJECT_ID") : System.getenv("GOOGLE_CLOUD_PROJECT");
        if (project == null || project.isBlank()) throw new IllegalStateException("PUBSUB_PROJECT_ID (or GOOGLE_CLOUD_PROJECT) is not set");
        return new PubSubBroker(project, System.getenv("PUBSUB_EMULATOR_HOST"));
    }

    @Override
    public String system() {
        return "gcp_pubsub";
    }

    private synchronized String bearer() throws Exception {
        if (emulator) return null;
        String fixed = System.getenv("GOOGLE_OAUTH_ACCESS_TOKEN");
        if (fixed != null && !fixed.isBlank()) return fixed;
        if (token == null || System.currentTimeMillis() > tokenExpiresAt) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(METADATA_TOKEN)).header("Metadata-Flavor", "Google").timeout(Duration.ofSeconds(5)).build();
            JsonNode body = JSON.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
            token = body.get("access_token").asText();
            tokenExpiresAt = System.currentTimeMillis() + (body.get("expires_in").asLong() - 60) * 1000;
        }
        return token;
    }

    /** Calls the API; a status listed in {@code accepted} returns null instead of failing. */
    private JsonNode call(String method, String path, Object body, int... accepted) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(root + path)).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        String bearer = bearer();
        if (bearer != null) req.header("Authorization", "Bearer " + bearer);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 == 2) return res.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(res.body());
        for (int status : accepted) if (res.statusCode() == status) return null;
        throw new IOException("Pub/Sub " + method + " " + path + " failed: " + res.statusCode() + " " + res.body());
    }

    @Override
    public void declare(Topology t) throws Exception {
        // 409: it already exists (created earlier or by infrastructure as code).
        call("PUT", "/topics/" + t.exchange(), Map.of(), 409);
        call("PUT", "/topics/" + t.dlx(), Map.of(), 409);
        call("PUT", "/subscriptions/" + t.queue(), Map.of("topic", "projects/" + project + "/topics/" + t.exchange(), "ackDeadlineSeconds", 60), 409);
        call("PUT", "/subscriptions/" + t.dlq(), Map.of("topic", "projects/" + project + "/topics/" + t.dlx(), "ackDeadlineSeconds", 60), 409);
    }

    private void publishTo(String topic, BrokerMessage m) throws Exception {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("ghk_message_id", m.messageId());
        if (m.eventType() != null) attributes.put("event_type", m.eventType());
        if (m.tenantId() != null) attributes.put("tenant_id", m.tenantId());
        if (m.traceparent() != null) attributes.put("traceparent", m.traceparent());
        JsonNode res = call("POST", "/topics/" + topic + ":publish",
            Map.of("messages", List.of(Map.of("data", Base64.getEncoder().encodeToString(m.body()), "attributes", attributes))));
        if (res.path("messageIds").size() != 1) throw new IOException("Pub/Sub did not accept message " + m.messageId());
    }

    @Override
    public EventPublisher publisher(String topic) {
        return new EventPublisher() {
            @Override
            public String system() {
                return "gcp_pubsub";
            }

            @Override
            public String destination() {
                return topic;
            }

            @Override
            public void publish(BrokerMessage m) throws Exception {
                publishTo(topic, m);
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public EventSubscription subscribe(String subscription, Topology topology) {
        return new EventSubscription() {
            @Override
            public String system() {
                return "gcp_pubsub";
            }

            @Override
            public String source() {
                return subscription;
            }

            @Override
            public Delivery poll() throws Exception {
                JsonNode received = call("POST", "/subscriptions/" + subscription + ":pull", Map.of("maxMessages", 1, "returnImmediately", true)).path("receivedMessages");
                if (received.size() == 0) return null;
                JsonNode msg = received.get(0).path("message");
                JsonNode attrs = msg.path("attributes");
                String ackId = received.get(0).path("ackId").asText();
                BrokerMessage m = new BrokerMessage(text(attrs, "ghk_message_id", msg.path("messageId").asText()), text(attrs, "event_type", null),
                    text(attrs, "tenant_id", null), text(attrs, "traceparent", null), Base64.getDecoder().decode(msg.path("data").asText("")));
                Action ack = () -> call("POST", "/subscriptions/" + subscription + ":acknowledge", Map.of("ackIds", List.of(ackId)));
                return new Delivery(m, ack, () -> {
                    publishTo(topology.dlx(), m);
                    ack.run();
                });
            }

            @Override
            public void close() {
            }
        };
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    @Override
    public void close() {
    }
}
