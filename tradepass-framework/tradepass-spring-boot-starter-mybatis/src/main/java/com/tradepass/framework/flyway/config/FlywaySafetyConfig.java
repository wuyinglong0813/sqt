package com.tradepass.framework.flyway.config;

import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

@Configuration
public class FlywaySafetyConfig {
    private static final String DESTRUCTIVE_VERSION = "5";
    private static final List<String> V5_DROPPED_TABLES = List.of(
            "delivery_receipt_item", "delivery_receipt", "delivery_item", "delivery_note",
            "trade_order_item", "contract_item", "contract_party_snapshot"
    );

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "tradepass.services.split", havingValue = "false", matchIfMissing = true)
    FlywayMigrationStrategy guardedFlywayMigrationStrategy(
            @Value("${tradepass.migration.allow-destructive-v5:false}") boolean allowDestructiveV5) {
        return flyway -> {
            boolean v5Pending = false;
            for (MigrationInfo migration : flyway.info().pending()) {
                if (migration.getVersion() != null
                        && DESTRUCTIVE_VERSION.equals(migration.getVersion().getVersion())) {
                    v5Pending = true;
                    break;
                }
            }
            if (v5Pending && !allowDestructiveV5) {
                assertDroppedTablesEmpty(flyway.getConfiguration().getDataSource());
            }
            flyway.migrate();
        };
    }

    static void assertDroppedTablesEmpty(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            for (String table : V5_DROPPED_TABLES) {
                if (tableExists(connection, table) && rowCount(connection, table) > 0) {
                    throw new IllegalStateException(
                            "检测到待执行的破坏性 V5 迁移，表 " + table + " 中仍有数据。"
                                    + "请先备份并制定人工迁移方案；确认可删除后，显式设置 "
                                    + "TRADEPASS_ALLOW_DESTRUCTIVE_V5=true。");
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("执行 V5 迁移安全检查失败", e);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = metadata.getTables(connection.getCatalog(), null, table.toUpperCase(),
                new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private static long rowCount(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            result.next();
            return result.getLong(1);
        }
    }
}
