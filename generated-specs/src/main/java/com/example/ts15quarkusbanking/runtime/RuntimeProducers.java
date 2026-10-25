package com.example.ts15quarkusbanking.runtime;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Wires the runtime kernel into CDI: connections from the Agroal pool (DATABASE_URL) and the CommandService. */
@ApplicationScoped
public class RuntimeProducers {
    @Produces
    @ApplicationScoped
    ConnectionFactory connectionFactory(AgroalDataSource dataSource) {
        return dataSource::getConnection;
    }

    /**
     * Creates the kernel's tables when the service starts, before it serves or relays anything: idempotent
     * and serialized across replicas. GHK_MIGRATE=false when a migration job owns the schema.
     */
    void migrate(@Observes StartupEvent event, ConnectionFactory connections, @ConfigProperty(name = "ghk.schema.migrate", defaultValue = "true") boolean migrate) throws java.sql.SQLException {
        if (migrate) Schema.migrate(connections, "public");
    }

    @Produces
    @ApplicationScoped
    CommandService commandService(ConnectionFactory connections) {
        return new CommandService(connections, "public");
    }
}
