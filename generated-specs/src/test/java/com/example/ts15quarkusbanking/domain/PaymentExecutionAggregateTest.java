package com.example.ts15quarkusbanking.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PaymentExecutionAggregateTest {

    @Test
    void startsInTheInitialStateWithNoEvents() {
        var aggregate = new PaymentExecutionAggregate("agg-1");
        assertEquals(PaymentExecutionState.PENDING, aggregate.getState());
        assertEquals(0, aggregate.getVersion());
        assertTrue(aggregate.getPendingEvents().isEmpty());
    }

    @Test
    void rejectsAnAggregateWithoutId() {
        assertThrows(DomainValidationException.class, () -> new PaymentExecutionAggregate(""));
    }

    @Test
    void executePaymentRecordsPaymentExecutedAndBumpsTheVersion() {
        var aggregate = new PaymentExecutionAggregate("agg-1");
        var event = aggregate.executePayment(new PaymentExecutionCommand("agg-1"));
        assertEquals(PaymentExecutionEventType.PaymentExecuted, event.type());
        assertEquals(1, event.version());
        assertEquals(1, aggregate.getVersion());
        assertEquals(1, aggregate.getPendingEvents().size());
    }

    @Test
    void executePaymentRejectsACommandWithoutId() {
        var aggregate = new PaymentExecutionAggregate("agg-1");
        assertThrows(DomainValidationException.class, () -> aggregate.executePayment(new PaymentExecutionCommand("")));
        assertTrue(aggregate.getPendingEvents().isEmpty());
    }
}
