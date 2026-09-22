package com.tradepass.framework.runtime.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "tradepass.services.split", havingValue = "true")
public class OwnedDatabaseConfiguration {
    @Bean FlywayMigrationStrategy ownedSchemaMigration(Environment environment) {
        return flyway -> {
            // Validate before Flyway can modify a mistakenly supplied database.
            try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
                validateConfiguration(connection.getCatalog(), environment);
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("Cannot validate the owned database before migration", failure);
            }
            flyway.migrate();
        };
    }

    private static void validateConfiguration(String database, Environment environment) {
        String role = environment.getRequiredProperty("tradepass.runtime.role");
        if (!environment.getProperty("seata.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Owned databases require Seata transaction coordination");
        }
        if (environment.getProperty("tradepass.demo-data.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Shared-database demo initialization is unavailable in split services; use isolated service fixtures");
        }
        if (database == null || !database.endsWith("_" + role)) {
            throw new IllegalStateException("The service database name must end with _" + role);
        }
    }

    @Bean SmartInitializingSingleton validateOwnedDatabase(DataSource dataSource, Environment environment) {
        return () -> {
            try (var connection = dataSource.getConnection()) {
                validateConfiguration(connection.getCatalog(), environment);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM undo_log")) {
                    if (!rows.next()) throw new IllegalStateException("Missing Seata rollback table");
                }
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("Owned database validation failed", failure);
            }
        };
    }
}
