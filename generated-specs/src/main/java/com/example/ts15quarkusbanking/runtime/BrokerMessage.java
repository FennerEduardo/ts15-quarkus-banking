package com.example.ts15quarkusbanking.runtime;

/** A domain event on the wire, independent of the broker. */
public record BrokerMessage(String messageId, String eventType, String tenantId, String traceparent, byte[] body) {}
