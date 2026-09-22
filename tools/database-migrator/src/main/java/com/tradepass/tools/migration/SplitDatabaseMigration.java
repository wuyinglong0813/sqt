package com.tradepass.tools.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import java.sql.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** Offline, insert-only cutover. The source connection is always read-only. */
public final class SplitDatabaseMigration {
    private static final Map<String, List<String>> AUDIT_TYPES = Map.of(
            "contract", List.of("CONTRACT", "CONTRACT_TEMPLATE"),
            "trade", List.of("ORDER", "LOGISTICS_DOCUMENT", "PERSONAL_MEMO", "BILATERAL_ACTION", "PROJECT_LEDGER",
                    "BUSINESS_DOCUMENT_TEMPLATE", "BUSINESS_DOCUMENT", "WAREHOUSE", "BUSINESS_DOCUMENT_RECEIPT", "INVENTORY_INBOUND"),
            "settlement", List.of("CONTRACT_ATTACHMENT", "RECONCILIATION_STATEMENT"));
    private record Target(String role, String url, String user, String password, Connection connection) { }
    private record Fingerprint(long rows, String sha256) { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || args.length == 1 && !Set.of("--plan", "--apply").contains(args[0])) {
            throw new IllegalArgumentException("Usage: migration.jar [--plan|--apply]; credentials are environment variables");
        }
        boolean apply = args.length == 1 && args[0].equals("--apply");
        if (apply && !"true".equals(System.getenv("TRADEPASS_CUTOVER_WRITES_PAUSED"))) {
            throw new IllegalStateException("Stop source and target business writers, then set TRADEPASS_CUTOVER_WRITES_PAUSED=true");
        }
        Map<String, List<String>> ownership;
        try (var input = SplitDatabaseMigration.class.getResourceAsStream("/table-ownership.json")) {
            ownership = new ObjectMapper().readValue(input, new TypeReference<>() { });
        }
        List<Target> targets = new ArrayList<>();
        String sourceUrl = required("SOURCE_DATABASE_URL");
        try (Connection source = DriverManager.getConnection(sourceUrl, required("SOURCE_DB_USERNAME"), required("SOURCE_DB_PASSWORD"))) {
            source.setReadOnly(true);
            source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            source.setAutoCommit(false);
            requireSourceVersion(source);
            Set<String> databases = new HashSet<>();
            databases.add(source.getCatalog());
            for (String role : ownership.keySet()) {
                String prefix = "TARGET_" + role.toUpperCase(Locale.ROOT);
                String url = required(prefix + "_DATABASE_URL"), user = required(prefix + "_DB_USERNAME"), password = required(prefix + "_DB_PASSWORD");
                Connection connection = DriverManager.getConnection(url, user, password);
                targets.add(new Target(role, url, user, password, connection));
                String database = connection.getCatalog();
                if (database == null || !database.endsWith("_" + role) || !databases.add(database)) {
                    throw new IllegalStateException("Source and targets must use distinct database names; target names must end with the service role");
                }
                requireEmpty(connection);
            }
            List<String> sourceTables = new ArrayList<>();
            ownership.values().forEach(sourceTables::addAll);
            sourceTables.add("audit_log");
            requireKnownTables(source, new HashSet<>(sourceTables));
            for (Target target : targets) {
                for (String table : ownership.get(target.role())) {
                    System.out.println(target.role() + ": " + table + " rows=" + count(source, table, ""));
                }
                System.out.println(target.role() + ": audit_log rows=" + count(source, "audit_log", auditFilter(target.role())));
            }
            if (!apply) {
                System.out.println("Plan complete. No target schema or data changed.");
                return;
            }
            for (Target target : targets) {
                Flyway.configure().dataSource(target.url(), target.user(), target.password())
                        .locations("classpath:db/owned/" + target.role()).baselineOnMigrate(false).load().migrate();
                target.connection().setAutoCommit(false);
                execute(target.connection(), "SET FOREIGN_KEY_CHECKS=0");
                // The baseline creates only system permission seed data. Replace it with the source's exact definitions.
                if (target.role().equals("identity")) execute(target.connection(), "DELETE FROM perm_def");
            }
            for (Target target : targets) {
                for (String table : ownership.get(target.role())) copyAndVerify(source, target, table, "");
                copyAndVerify(source, target, "audit_log", auditFilter(target.role()));
            }
            // Nothing is committed until every table has passed its row count and content hash comparison.
            for (Target target : targets) {
                execute(target.connection(), "SET FOREIGN_KEY_CHECKS=1");
                target.connection().commit();
                System.out.println("Committed " + target.role());
            }
            System.out.println("Copy verified. Keep the source read-only until the new services pass the cutover checks.");
        } finally {
            for (Target target : targets) {
                try { if (!target.connection().getAutoCommit()) target.connection().rollback(); }
                finally { target.connection().close(); }
            }
        }
    }

    private static void copyAndVerify(Connection source, Target target, String table, String filter) throws Exception {
        List<String> columns = new ArrayList<>(), primary = new ArrayList<>();
        try (var metadata = source.getMetaData().getColumns(source.getCatalog(), null, table, null)) {
            while (metadata.next()) if (!"YES".equals(metadata.getString("IS_GENERATEDCOLUMN"))) columns.add(metadata.getString("COLUMN_NAME"));
        }
        try (var metadata = source.getMetaData().getPrimaryKeys(source.getCatalog(), null, table)) {
            Map<Short, String> keys = new TreeMap<>();
            while (metadata.next()) keys.put(metadata.getShort("KEY_SEQ"), metadata.getString("COLUMN_NAME"));
            primary.addAll(keys.values());
        }
        if (columns.isEmpty() || primary.isEmpty()) throw new IllegalStateException("Missing source columns or primary key for " + table);
        String projection = String.join(",", columns.stream().map(SplitDatabaseMigration::identifier).toList());
        String order = String.join(",", primary.stream().map(SplitDatabaseMigration::identifier).toList());
        String select = "SELECT " + projection + " FROM " + identifier(table) + filter + " ORDER BY " + order;
        String insert = "INSERT INTO " + identifier(table) + " (" + projection + ") VALUES ("
                + String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
        Fingerprint copied;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (var reader = source.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             var writer = target.connection().prepareStatement(insert)) {
            reader.setFetchSize(Integer.MIN_VALUE);
            try (var rows = reader.executeQuery(select)) {
                while (rows.next()) {
                    hashRow(rows, columns.size(), digest);
                    for (int column = 1; column <= columns.size(); column++) writer.setObject(column, rows.getObject(column));
                    writer.executeUpdate();
                    count++;
                }
            }
        }
        copied = new Fingerprint(count, HexFormat.of().formatHex(digest.digest()));
        Fingerprint stored = fingerprint(target.connection(), "SELECT " + projection + " FROM " + identifier(table) + " ORDER BY " + order, columns.size());
        if (!copied.equals(stored)) throw new IllegalStateException("Content verification failed: " + target.role() + "." + table);
        System.out.println("Verified " + target.role() + "." + table + " rows=" + count + " sha256=" + copied.sha256());
    }

    private static Fingerprint fingerprint(Connection connection, String sql, int columns) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (var statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(Integer.MIN_VALUE);
            try (var rows = statement.executeQuery(sql)) {
                while (rows.next()) { hashRow(rows, columns, digest); count++; }
            }
        }
        return new Fingerprint(count, HexFormat.of().formatHex(digest.digest()));
    }
    private static void hashRow(ResultSet rows, int columns, MessageDigest digest) throws SQLException {
        for (int column = 1; column <= columns; column++) {
            byte[] bytes = rows.getBytes(column);
            digest.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
            if (bytes != null) digest.update(bytes);
        }
    }
    private static void requireSourceVersion(Connection source) throws SQLException {
        try (var statement = source.createStatement(); var rows = statement.executeQuery(
                "SELECT MAX(CAST(version AS UNSIGNED)), SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) FROM flyway_schema_history")) {
            if (!rows.next() || rows.getInt(1) != 36 || rows.getInt(2) != 0) throw new IllegalStateException("Source must have successfully completed V36");
        }
    }
    private static void requireEmpty(Connection target) throws SQLException {
        try (var tables = target.getMetaData().getTables(target.getCatalog(), null, "%", new String[]{"TABLE"})) {
            if (tables.next()) throw new IllegalStateException("Target must be a new empty database; refusing to modify existing schema " + target.getCatalog());
        }
    }
    private static void requireKnownTables(Connection source, Set<String> expected) throws SQLException {
        var actual = new HashSet<String>();
        try (var tables = source.getMetaData().getTables(source.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) actual.add(tables.getString("TABLE_NAME"));
        }
        actual.remove("flyway_schema_history");
        if (!actual.equals(expected)) throw new IllegalStateException("Source table inventory differs from the reviewed ownership manifest");
    }
    private static String auditFilter(String role) {
        var types = role.equals("identity") ? AUDIT_TYPES.values().stream().flatMap(Collection::stream).toList() : AUDIT_TYPES.get(role);
        return " WHERE biz_type " + (role.equals("identity") ? "NOT IN" : "IN") + " ("
                + String.join(",", types.stream().map(type -> "'" + type + "'").toList()) + ")";
    }
    private static long count(Connection connection, String table, String filter) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + identifier(table) + filter)) {
            rows.next(); return rows.getLong(1);
        }
    }
    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static String identifier(String name) {
        if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid database identifier");
        return "`" + name + "`";
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
