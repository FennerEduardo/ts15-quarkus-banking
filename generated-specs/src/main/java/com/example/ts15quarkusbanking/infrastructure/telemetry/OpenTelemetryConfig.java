package com.example.ts15quarkusbanking.infrastructure.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
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
 * With OTEL_EXPORTER_OTLP_ENDPOINT set, registers an OTLP/HTTP exporter (service.name from OTEL_SERVICE_NAME) as the
 * global OpenTelemetry the runtime kernel traces with, unless one is already registered (agent or test harness).
 * Without it nothing is exported and nothing connects.
 */
@ApplicationScoped
public class OpenTelemetryConfig {
    void onStart(@Observes StartupEvent event) {
        String endpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) return;
        String service = System.getenv().getOrDefault("OTEL_SERVICE_NAME", "ts15-quarkus-banking");
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), service))))
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
