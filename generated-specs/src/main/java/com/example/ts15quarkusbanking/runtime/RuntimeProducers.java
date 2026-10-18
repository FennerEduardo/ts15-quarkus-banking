package com.example.ts15quarkusbanking.runtime;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** Wires the runtime kernel into CDI: connections from the Agroal pool (DATABASE_URL) and the CommandService. */
@ApplicationScoped
public class RuntimeProducers {
    @Produces
    @ApplicationScoped
    ConnectionFactory connectionFactory(AgroalDataSource dataSource) {
        return dataSource::getConnection;
    }

    @Produces
    @ApplicationScoped
    CommandService commandService(ConnectionFactory connections) {
        return new CommandService(connections, "public");
    }
}
