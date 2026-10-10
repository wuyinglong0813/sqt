package com.tradepass.tools.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import java.sql.*;
import java.util.*;

/** Offline copy into a NEW business schema. Never modifies the three source databases. */
public final class MergeBusinessMigration {
    private static final List<String> ROLES = List.of("contract", "trade", "settlement");

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || args.length == 1 && !Set.of("--plan", "--apply").contains(args[0])) {
            throw new IllegalArgumentException("Usage: migration.jar --merge-business [--plan|--apply]");
        }
        boolean apply = args.length == 1 && args[0].equals("--apply");
        if (apply && !"true".equals(System.getenv("TRADEPASS_CUTOVER_WRITES_PAUSED"))) {
            throw new IllegalStateException("Pause all business writers and drain Seata transactions before applying");
        }
        Map<String, List<String>> ownership;
        try (var input = MergeBusinessMigration.class.getResourceAsStream("/table-ownership.json")) {
            ownership = new ObjectMapper().readValue(input, new TypeReference<>() {});
        }
        var sources = new LinkedHashMap<String, Connection>();
        String targetUrl = required("TARGET_BUSINESS_DATABASE_URL");
        String targetUser = required("TARGET_BUSINESS_DB_USERNAME"), targetPassword = required("TARGET_BUSINESS_DB_PASSWORD");
        try (Connection target = DriverManager.getConnection(targetUrl, targetUser, targetPassword)) {
            String database = target.getCatalog();
            if (database == null || !database.endsWith("_business") || !tables(target).isEmpty()) {
                throw new IllegalStateException("Target must be a new EMPTY _business database");
            }
            for (String role : ROLES) {
                String prefix = "SOURCE_" + role.toUpperCase(Locale.ROOT);
                Connection source = DriverManager.getConnection(required(prefix + "_DATABASE_URL"),
                        required(prefix + "_DB_USERNAME"), required(prefix + "_DB_PASSWORD"));
                sources.put(role, source);
                source.setReadOnly(true);
                source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                source.setAutoCommit(false);
                if (source.getCatalog() == null || !source.getCatalog().endsWith("_" + role)) {
                    throw new IllegalStateException("Incorrect source schema for " + role);
                }
                var expected = new HashSet<>(ownership.get(role));
                expected.addAll(List.of("audit_log", "undo_log", "flyway_schema_history"));
                if (!tables(source).equals(expected)) throw new IllegalStateException("Unreviewed source table inventory: " + role);
                try (var statement = source.createStatement(); var rows = statement.executeQuery(
                        "SELECT COUNT(*), MAX(CAST(version AS UNSIGNED)), SUM(success = 0) FROM flyway_schema_history WHERE version IS NOT NULL")) {
                    boolean trade = role.equals("trade");
                    boolean contract = role.equals("contract");
                    if (!rows.next() || rows.getInt(3) != 0
                            || (trade ? rows.getInt(1) != 3 || rows.getInt(2) != 3
                            : contract ? rows.getInt(1) != 4 || rows.getInt(2) != 4
                            : !((rows.getInt(1) == 1 && rows.getInt(2) == 1)
                            || (rows.getInt(1) == 2 && rows.getInt(2) == 2)))) {
                        throw new IllegalStateException("Source must match reviewed owned migrations (trade V3; contract V4; settlement V1 or V2): " + role);
                    }
                }
                if (count(source, "undo_log") != 0) throw new IllegalStateException("Pending Seata undo records: " + role);
                for (String table : ownership.get(role)) System.out.println(role + "." + table + " rows=" + count(source, table));
                System.out.println(role + ".audit_log rows=" + count(source, "audit_log"));
            }
            if (!apply) { System.out.println("Plan complete. No schema or data changed."); return; }
            Flyway.configure().dataSource(targetUrl, targetUser, targetPassword).locations("classpath:db/business")
                    .baselineOnMigrate(false).load().migrate();
            target.setAutoCommit(false);
            try {
                try (var statement = target.createStatement()) { statement.executeUpdate("DELETE FROM membership_policy_state"); }
                long auditRows = 0;
                for (String role : ROLES) {
                    Connection source = sources.get(role);
                    for (String table : ownership.get(role)) {
                        long copied = copyVerified(source, target, table);
                        if (count(target, table) != copied) throw new IllegalStateException("Row count mismatch: " + table);
                    }
                    // IDs are preserved; a duplicate audit ID fails instead of silently losing a record.
                    auditRows += copyVerified(source, target, "audit_log");
                }
                if (count(target, "audit_log") != auditRows) throw new IllegalStateException("Audit count mismatch");
                verifyRelationships(target);
                target.commit();
                System.out.println("Business copy committed after exact row verification. Original databases unchanged.");
            } catch (Exception failure) {
                target.rollback();
                throw failure;
            }
        } finally {
            for (Connection source : sources.values()) source.close();
        }
    }

    private static long copyVerified(Connection source, Connection target, String table) throws Exception {
        var columns = new ArrayList<String>();
        var keys = new TreeMap<Short, String>();
        try (var rows = source.getMetaData().getColumns(source.getCatalog(), null, table, null)) {
            while (rows.next()) if (!"YES".equals(rows.getString("IS_GENERATEDCOLUMN"))) columns.add(rows.getString("COLUMN_NAME"));
        }
        try (var rows = source.getMetaData().getPrimaryKeys(source.getCatalog(), null, table)) {
            while (rows.next()) keys.put(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME"));
        }
        if (columns.isEmpty() || keys.isEmpty()) throw new IllegalStateException("Missing primary key: " + table);
        String projection = String.join(",", columns.stream().map(MergeBusinessMigration::id).toList());
        String predicate = String.join(" AND ", keys.values().stream().map(key -> id(key) + "=?").toList());
        String insert = "INSERT INTO " + id(table) + " (" + projection + ") VALUES (" + String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
        long count = 0;
        try (var reader = source.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             var writer = target.prepareStatement(insert);
             var verifier = target.prepareStatement("SELECT " + projection + " FROM " + id(table) + " WHERE " + predicate)) {
            reader.setFetchSize(Integer.MIN_VALUE);
            try (var rows = reader.executeQuery("SELECT " + projection + " FROM " + id(table))) {
                while (rows.next()) {
                    for (int c = 1; c <= columns.size(); c++) writer.setObject(c, rows.getObject(c));
                    writer.executeUpdate();
                    int index = 1;
                    for (String key : keys.values()) verifier.setObject(index++, rows.getObject(key));
                    try (var stored = verifier.executeQuery()) {
                        if (!stored.next()) throw new IllegalStateException("Missing copied row: " + table);
                        for (int c = 1; c <= columns.size(); c++) {
                            if (!Arrays.equals(rows.getBytes(c), stored.getBytes(c))) {
                                throw new IllegalStateException("Content mismatch: " + table + "." + columns.get(c - 1));
                            }
                        }
                    }
                    count++;
                }
            }
        }
        System.out.println("Verified " + source.getCatalog() + "." + table + " rows=" + count);
        return count;
    }

    private static void verifyRelationships(Connection target) throws SQLException {
        // Preserve the offline copy's consistency checks without database constraints.
        for (String[] relation : List.of(
                new String[]{"project_contract_assignment", "project_id", "project_ledger"},
                new String[]{"project_contract_assignment", "contract_id", "trade_contract"},
                new String[]{"fadada_contract_sign_task", "contract_id", "trade_contract"},
                new String[]{"project_contract_prompt_preference", "contract_id", "trade_contract"})) {
            String sql = "SELECT COUNT(*) FROM " + id(relation[0]) + " c WHERE c." + id(relation[1])
                    + " IS NOT NULL AND NOT EXISTS (SELECT 1 FROM " + id(relation[2])
                    + " p WHERE p.id=c." + id(relation[1]) + ")";
            try (var statement = target.createStatement(); var rows = statement.executeQuery(sql)) {
                rows.next();
                if (rows.getLong(1) != 0) throw new IllegalStateException("Orphaned business reference: " + relation[0]);
            }
        }
    }

    private static Set<String> tables(Connection connection) throws SQLException {
        var result = new HashSet<String>();
        try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rows.next()) result.add(rows.getString("TABLE_NAME"));
        }
        return result;
    }
    private static long count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + id(table))) {
            rows.next(); return rows.getLong(1);
        }
    }
    private static String id(String name) {
        if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid SQL identifier");
        return "`" + name + "`";
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
