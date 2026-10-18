package com.example.ts15quarkusbanking.runtime;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import java.util.HashMap;
import java.util.Map;

/** RabbitMQ 0-9-1: topic exchange, publisher confirms, dead-lettering through the queue's DLX. */
public final class RabbitMqBroker implements Broker {
    private final com.rabbitmq.client.Connection connection;

    public RabbitMqBroker(com.rabbitmq.client.Connection connection) {
        this.connection = connection;
    }

    public static RabbitMqBroker connect(String url) throws Exception {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setUri(url);
        factory.setConnectionTimeout(30_000);
        factory.setHandshakeTimeout(30_000);
        // A busy host can delay the AMQP handshake: retry before failing.
        for (int attempt = 1; ; attempt++) {
            try {
                return new RabbitMqBroker(factory.newConnection());
            } catch (java.io.IOException | java.util.concurrent.TimeoutException e) {
                if (attempt == 5) throw e;
                Thread.sleep(2000L * attempt);
            }
        }
    }

    @Override
    public String system() {
        return "rabbitmq";
    }

    @Override
    public void declare(Topology t) throws Exception {
        try (Channel channel = connection.createChannel()) {
            channel.exchangeDeclare(t.exchange(), "topic", true);
            channel.exchangeDeclare(t.dlx(), "fanout", true);
            channel.queueDeclare(t.dlq(), true, false, false, null);
            channel.queueBind(t.dlq(), t.dlx(), "");
            channel.queueDeclare(t.queue(), true, false, false, Map.of("x-dead-letter-exchange", t.dlx()));
            channel.queueBind(t.queue(), t.exchange(), "#");
        }
    }

    @Override
    public EventPublisher publisher(String exchange) throws Exception {
        Channel channel = connection.createChannel();
        channel.confirmSelect();
        return new EventPublisher() {
            @Override
            public String system() {
                return "rabbitmq";
            }

            @Override
            public String destination() {
                return exchange;
            }

            @Override
            public void publish(BrokerMessage m) throws Exception {
                Map<String, Object> headers = new HashMap<>();
                if (m.tenantId() != null) headers.put("tenant_id", m.tenantId());
                if (m.traceparent() != null) headers.put("traceparent", m.traceparent());
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .messageId(m.messageId()).deliveryMode(2).contentType("application/json").type(m.eventType()).headers(headers).build();
                channel.basicPublish(exchange, m.eventType() == null ? "" : m.eventType(), props, m.body());
                channel.waitForConfirmsOrDie(10_000);
            }

            @Override
            public void close() throws Exception {
                if (channel.isOpen()) channel.close();
            }
        };
    }

    @Override
    public EventSubscription subscribe(String queue, Topology topology) throws Exception {
        Channel channel = connection.createChannel();
        return new EventSubscription() {
            @Override
            public String system() {
                return "rabbitmq";
            }

            @Override
            public String source() {
                return queue;
            }

            @Override
            public Delivery poll() throws Exception {
                GetResponse r = channel.basicGet(queue, false);
                if (r == null) return null;
                var props = r.getProps();
                Map<String, Object> h = props.getHeaders() == null ? Map.of() : props.getHeaders();
                long tag = r.getEnvelope().getDeliveryTag();
                BrokerMessage m = new BrokerMessage(props.getMessageId(), props.getType(), text(h.get("tenant_id")), text(h.get("traceparent")), r.getBody());
                // Rejected without requeue: RabbitMQ dead-letters it through the queue's DLX.
                return new Delivery(m, () -> channel.basicAck(tag, false), () -> channel.basicNack(tag, false, false));
            }

            @Override
            public void close() throws Exception {
                if (channel.isOpen()) channel.close();
            }
        };
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    @Override
    public void close() throws Exception {
        if (connection.isOpen()) connection.close();
    }
}
