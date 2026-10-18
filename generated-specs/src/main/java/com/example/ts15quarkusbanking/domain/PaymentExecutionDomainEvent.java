package com.example.ts15quarkusbanking.domain;

import java.time.Instant;
import java.util.Map;

public record PaymentExecutionDomainEvent(PaymentExecutionEventType type, String aggregateId, long version, Instant occurredOn, Map<String, Object> payload) {
}
