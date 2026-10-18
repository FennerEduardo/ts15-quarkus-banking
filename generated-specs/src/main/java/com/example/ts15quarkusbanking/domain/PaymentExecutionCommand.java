package com.example.ts15quarkusbanking.domain;

import java.util.Map;

public record PaymentExecutionCommand(String id, Map<String, Object> payload) {
    public PaymentExecutionCommand(String id) {
        this(id, Map.of());
    }
}
