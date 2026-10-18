package com.example.ts15quarkusbanking.infrastructure.telemetry;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Registers an OTLP/HTTP exporter (OTEL_EXPORTER_OTLP_ENDPOINT, default http://localhost:4318) as the global
 * OpenTelemetry the runtime kernel traces with, unless one is already registered (agent or test harness).
 */
@ApplicationScoped
public class OpenTelemetryConfig {
    void onStart(@Observes StartupEvent event) {
        String endpoint = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318");
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder().setEndpoint(endpoint.replaceAll("/+$", "") + "/v1/traces").build()).build())
            .build();
        try {
            OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();
        } catch (IllegalStateException alreadyRegistered) {
            // Keep the registered one (OpenTelemetry Java agent, or a test harness).
            tracerProvider.close();
        }
    }
}
