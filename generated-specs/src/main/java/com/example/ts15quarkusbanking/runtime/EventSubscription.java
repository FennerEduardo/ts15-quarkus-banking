package com.example.ts15quarkusbanking.runtime;

/** Pulls messages from one queue / subscription. */
public interface EventSubscription extends AutoCloseable {
    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    /** A received message: ack() once processed; deadLetter() moves it to the DLQ (it is not redelivered). */
    record Delivery(BrokerMessage message, Action ack, Action deadLetter) {}

    String system();

    String source();

    /** The next waiting message, or null when there is none. */
    Delivery poll() throws Exception;
}
