package com.tradepass.integration;

import com.tradepass.support.RepoRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Verify the migration paths used by production, including upgrades from owned V2. */
@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlRetailOwnedMigrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"business", "trade"})
    void existingOwnedDatabaseUpgradesAndRepeatedStartupPreservesRetailData(String role) {
        String url = System.getProperty("tradepass.test.mysql.url");
        if (!url.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?")) {
            throw new IllegalArgumentException("Only a dedicated tradepass_fix_validation_* database is allowed");
        }
        String username = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        String database = "tradepass_fix_validation_owned_" + role + "_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        String ownedUrl = url.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + database);
        var source = new DriverManagerDataSource(ownedUrl, username, password);
        var jdbc = new JdbcTemplate(source);
        String path = role.equals("business")
                ? "tradepass-business/src/main/resources/db/business"
                : "tradepass-module-trade/tradepass-module-trade-server/src/main/resources/db/owned/trade";
        String location = "filesystem:" + RepoRoot.find().resolve(path).toAbsolutePath();
        Flyway.configure().dataSource(source).locations(location).target("2").load().migrate();
        jdbc.update("INSERT INTO warehouse(id,company_id,name,created_by) VALUES (987654,3,'existing warehouse',7)");

        var flyway = Flyway.configure().dataSource(source).locations(location).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
        assertThat(jdbc.queryForObject("SELECT name FROM warehouse WHERE id=987654", String.class))
                .isEqualTo("existing warehouse");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
                AND table_name IN ('retail_customer','retail_document','retail_document_item')
                """, Integer.class)).isEqualTo(3);
        jdbc.update("INSERT INTO retail_customer(id,company_id,customer_type,name,created_by) VALUES (1,3,'PERSON','零售客户',7)");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(jdbc.queryForObject("SELECT name FROM retail_customer WHERE id=1", String.class)).isEqualTo("零售客户");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE constraint_schema=DATABASE() AND constraint_type='FOREIGN KEY'
                """, Integer.class)).isZero();
    }
}
