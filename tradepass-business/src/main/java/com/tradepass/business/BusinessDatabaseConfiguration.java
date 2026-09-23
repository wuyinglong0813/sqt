package com.tradepass.business;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One DataSource and Spring transaction manager for all hosted business modules. */
@Configuration(proxyBeanMethods = false)
public class BusinessDatabaseConfiguration {
    @Bean FlywayMigrationStrategy businessSchemaMigration() {
        return flyway -> {
            // Reject an old owned schema before Flyway can write to it.
            try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
                String database = connection.getCatalog();
                if (database == null || !database.endsWith("_business")) {
                    throw new IllegalStateException("Business requires a dedicated _business database; migrate existing owned databases first");
                }
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("Cannot validate the business database", failure);
            }
            flyway.migrate();
        };
    }
}
