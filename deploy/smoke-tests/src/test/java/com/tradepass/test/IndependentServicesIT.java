package com.tradepass.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Boots the actual six release jars. All writes are restricted to an explicitly isolated test schema. */
@EnabledIfSystemProperty(named = "tradepass.test.services.url", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IndependentServicesIT {
    static final List<String> ROLES = List.of("identity", "contract", "trade", "settlement", "file", "gateway");
    static final Map<String, Integer> PORTS = new LinkedHashMap<>(), MANAGEMENT = new LinkedHashMap<>();
    static final Map<String, Integer> COVERAGE = new LinkedHashMap<>();
    static final Map<String, Process> PROCESSES = new LinkedHashMap<>();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final ObjectMapper JSON = new ObjectMapper();
    static final String KEY = UUID.randomUUID() + "-integration", TOKEN = UUID.randomUUID().toString();
    static final long BASE = System.currentTimeMillis(), USER = BASE + 1, SUPPLIER = BASE + 2,
            BUYER = BASE + 3, CONTRACT = BASE + 4, WAREHOUSE = BASE + 5, DOCUMENT = BASE + 6;
    static Path root, logs;
    static JdbcTemplate jdbc;
    static final Map<String, JdbcTemplate> OWNED = new LinkedHashMap<>();
    static final Map<String, String> DATABASE_URLS = new LinkedHashMap<>(), DATABASE_USERS = new LinkedHashMap<>(), DATABASE_PASSWORDS = new LinkedHashMap<>();
    static String url, username, password;
    static com.alibaba.nacos.api.config.ConfigService nacosConfig;
    static com.alibaba.nacos.api.naming.NamingService nacosNaming;
    static String nacosGroup;
    static final List<String> NACOS_DATA_IDS = new ArrayList<>();

    @BeforeAll static void start() throws Exception {
        root = com.tradepass.support.RepoRoot.find();
        logs = Path.of("target/process-logs").toAbsolutePath();
        Files.createDirectories(logs);
        url = System.getProperty("tradepass.test.services.url");
        assertTrue(url.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(\\?.*)?"), "Isolated schema required");
        assertNotEquals(url, System.getProperty("tradepass.test.mysql.url"));
        username = System.getProperty("tradepass.test.mysql.username");
        password = System.getProperty("tradepass.test.mysql.password");
        String migrationLocation = "filesystem:" + com.tradepass.support.RepoRoot.legacyMysqlMigrations().toAbsolutePath();
        Flyway.configure().dataSource(url, username, password).locations(migrationLocation).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        seed();
        prepareOwnedDatabases();
        for (String role : ROLES) { PORTS.put(role, freePort()); MANAGEMENT.put(role, freePort()); COVERAGE.put(role, freePort()); }
        try {
            configureNacos();
            for (String role : ROLES) launch(role, false);
            for (String role : ROLES) ready(role);
        } catch (Throwable error) { stop(); throw error; }
    }

    static void prepareOwnedDatabases() throws Exception {
        assertTrue(System.getProperty("tradepass.test.seata.server", "").matches("(127\\.0\\.0\\.1|localhost):[0-9]+"), "An isolated local Seata coordinator is required");
        String sourceDatabase = url.substring(url.indexOf('/', "jdbc:mysql://".length()) + 1).split("\\?", 2)[0];
        for (String role : ROLES.subList(0, 4)) {
            String schema = sourceDatabase + "_" + role;
            String user = "ci_" + role + "_" + BASE;
            String secret = UUID.randomUUID().toString().replace("-", "");
            jdbc.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            jdbc.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + secret + "'");
            jdbc.execute("GRANT ALL PRIVILEGES ON `" + schema + "`.* TO '" + user + "'@'%'");
            String target = url.replace("/" + sourceDatabase, "/" + schema);
            DATABASE_URLS.put(role, target); DATABASE_USERS.put(role, user); DATABASE_PASSWORDS.put(role, secret);
            OWNED.put(role, new JdbcTemplate(new DriverManagerDataSource(target, user, secret)));
        }
        migrate("--plan");
        for (var database : OWNED.values()) assertEquals(0, database.queryForList("SHOW TABLES").size(), "Plan must not modify targets");
        migrate("--apply");
        assertEquals(1, OWNED.get("identity").queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?", Integer.class, USER));
        assertEquals(1, OWNED.get("contract").queryForObject("SELECT COUNT(*) FROM trade_contract WHERE id=?", Integer.class, CONTRACT));
        assertArrayEquals(new byte[]{0, 1, (byte) 255, 10}, OWNED.get("settlement")
                .queryForObject("SELECT file_data FROM contract_attachment WHERE id=?", byte[].class, BASE + 80));
        assertEquals(new java.math.BigDecimal("12345678901234.23"), OWNED.get("settlement")
                .queryForObject("SELECT voucher_amount FROM contract_attachment WHERE id=?", java.math.BigDecimal.class, BASE + 80));
        for (String role : OWNED.keySet()) assertEquals(1, OWNED.get(role).queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class));
        // A real database grant boundary: no participant can bypass HTTP and join a foreign schema.
        for (String owner : OWNED.keySet()) for (String other : OWNED.keySet()) if (!owner.equals(other)) {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> OWNED.get(owner)
                    .queryForList("SELECT * FROM `" + sourceDatabase + "_" + other + "`.undo_log"));
        }
        jdbc = OWNED.get("trade");
    }

    static void migrate(String mode) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-jar",
                root.resolve("tools/database-migrator/target/tradepass-database-migrator-0.1.0-SNAPSHOT.jar").toString(), mode)
                .redirectErrorStream(true).redirectOutput(logs.resolve("migration" + mode + ".log").toFile());
        Map<String, String> env = builder.environment();
        env.keySet().removeIf(key -> key.startsWith("SOURCE_") || key.startsWith("TARGET_") || key.startsWith("TRADEPASS_")
                || key.equals("JAVA_TOOL_OPTIONS") || key.equals("JDK_JAVA_OPTIONS"));
        env.put("SOURCE_DATABASE_URL", url); env.put("SOURCE_DB_USERNAME", username); env.put("SOURCE_DB_PASSWORD", password);
        env.put("TRADEPASS_CUTOVER_WRITES_PAUSED", "true");
        for (String role : DATABASE_URLS.keySet()) {
            String prefix = "TARGET_" + role.toUpperCase(Locale.ROOT);
            env.put(prefix + "_DATABASE_URL", DATABASE_URLS.get(role));
            env.put(prefix + "_DB_USERNAME", DATABASE_USERS.get(role));
            env.put(prefix + "_DB_PASSWORD", DATABASE_PASSWORDS.get(role));
        }
        Process process = builder.start();
        boolean done = process.waitFor(90, TimeUnit.SECONDS);
        if (!done) process.destroyForcibly();
        assertTrue(done, "Migration timed out");
        assertEquals(0, process.exitValue(), "Migration failed; inspect " + logs.resolve("migration" + mode + ".log"));
    }

    static int freePort() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }

    static void launch(String role, boolean storage) throws Exception {
        String agent = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
                .findFirst().orElseThrow(() -> new IllegalStateException("JaCoCo agent is required for service coverage"))
                .substring("-javaagent:".length()).split("=", 2)[0];
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-javaagent:" + agent + "=output=tcpserver,address=127.0.0.1,port=" + COVERAGE.get(role) + ",includes=com.tradepass.*",
                "-Xms32m", "-Xmx224m", "-XX:ActiveProcessorCount=2", "-jar",
                root.resolve(role.equals("gateway") ? "tradepass-gateway/target/tradepass-gateway-0.1.0-SNAPSHOT.jar" : "tradepass-module-" + role + "/tradepass-module-" + role + "-server/target/tradepass-module-" + role + "-server-0.1.0-SNAPSHOT.jar").toString(),
                "--server.port=" + PORTS.get(role), "--management.server.port=" + MANAGEMENT.get(role),
                "--spring.profiles.active=" + (nacosConfig == null ? "observability" : "observability,nacos,sentinel"),
                "--management.server.address=127.0.0.1", "--management.endpoints.web.exposure.include=health,mappings,beans,prometheus",
                "--management.endpoint.health.enabled=true", "--management.endpoint.mappings.enabled=true",
                "--management.endpoint.beans.enabled=true", "--management.endpoint.prometheus.enabled=true",
                "--management.health.redis.enabled=false", "--spring.datasource.url=" + DATABASE_URLS.getOrDefault(role, url),
                "--spring.datasource.username=" + DATABASE_USERS.getOrDefault(role, username),
                "--spring.datasource.password=" + DATABASE_PASSWORDS.getOrDefault(role, password),
                "--seata.service.grouplist.default=" + System.getProperty("tradepass.test.seata.server"),
                "--tradepass.services.internal-key=" + KEY,
                "--tradepass.ids.datacenter-id=1", "--tradepass.ids.worker-id=" + (ROLES.indexOf(role) + 1),
                "--tradepass.storage.enabled=" + storage, "--tradepass.storage.required=false",
                "--tradepass.demo-data.enabled=false", "--tradepass.dev.enabled=false", "--tradepass.redis.enabled=false",
                "--tradepass.experience-test-accounts.enabled=false", "--tradepass.fadada.enabled=false",
                "--spring.flyway.enabled=" + (!role.equals("gateway") && !role.equals("file"))));
        if (nacosConfig == null) {
            for (String target : ROLES) command.add("--tradepass.services." + target + "-url=http://127.0.0.1:" + PORTS.get(target));
        } else {
            command.add("--NACOS_SERVER_ADDR=" + System.getProperty("tradepass.test.nacos.server"));
            command.add("--NACOS_GROUP=" + nacosGroup);
            command.add("--spring.cloud.nacos.discovery.ip=127.0.0.1");
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(logs.resolve(role + (storage ? "-storage-failure" : "") + ".log").toFile());
        // Local secrets or profiles must never redirect the isolated test processes to a business database.
        builder.environment().keySet().removeIf(k -> k.startsWith("SPRING_") || k.startsWith("TRADEPASS_")
                || k.startsWith("FADADA_") || k.startsWith("CLOUDBASE_") || k.startsWith("WECHAT_")
                || k.startsWith("NACOS_") || k.startsWith("OSS_") || k.startsWith("SENTINEL_")
                || k.startsWith("SEATA_") || k.startsWith("ROCKETMQ_") || k.startsWith("XXL_") || k.startsWith("DB_")
                || k.equals("JAVA_TOOL_OPTIONS") || k.equals("JDK_JAVA_OPTIONS"));
        PROCESSES.put(role, builder.start());
    }

    static void ready(String role) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(PROCESSES.get(role).isAlive(), role + " exited; inspect " + logs.resolve(role + ".log"));
            try { if (request(MANAGEMENT.get(role), "GET", "/actuator/health", null, Map.of()).statusCode() == 200) return; }
            catch (Exception ignored) { }
            Thread.sleep(250);
        }
        fail(role + " did not become ready; inspect " + logs);
    }

    @AfterAll static void stop() throws Exception {
        for (String role : PROCESSES.keySet()) if (PROCESSES.get(role).isAlive()) {
            try { dumpCoverage(role); }
            catch (java.io.IOException failed) { System.err.println("Service coverage dump failed: " + role); }
        }
        PROCESSES.values().forEach(Process::destroy);
        for (Process process : PROCESSES.values()) if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        if (nacosConfig != null) {
            try { for (String id : NACOS_DATA_IDS) nacosConfig.removeConfig(id, nacosGroup); }
            finally { nacosConfig.shutDown(); nacosConfig = null; }
        }
        if (nacosNaming != null) { nacosNaming.shutDown(); nacosNaming = null; }
    }

    @Test @Order(1) void sixSeparateProcessesAndExactlyOneOwnerPerLegacyApi() throws Exception {
        assertEquals(6, PROCESSES.values().stream().map(Process::pid).distinct().count());
        Set<String> actual = new TreeSet<>();
        for (String role : ROLES.subList(0, 5)) {
            JsonNode contexts = json(request(MANAGEMENT.get(role), "GET", "/actuator/mappings", null, Map.of())).path("contexts");
            int count = 0;
            for (JsonNode context : contexts) {
                for (JsonNode servlet : context.path("mappings").path("dispatcherServlets")) {
                    for (JsonNode mapping : servlet) {
                        if (!mapping.path("details").path("handlerMethod").path("className").asText().startsWith("com.tradepass.")) continue;
                        String predicate = mapping.path("predicate").asText();
                        if (predicate.contains("/internal/")) continue;
                        if (predicate.contains("/tcb_probe") && !role.equals("identity")) continue;
                        assertTrue(actual.add(predicate), "Duplicate API owner: " + predicate);
                        count++;
                    }
                }
            }
            assertTrue(count > 0, role + " has no public API");
        }
        Set<String> expected = new TreeSet<>();
        for (String line : Files.readAllLines(root.resolve("deploy/smoke-tests/src/test/resources/architecture/http-api-baseline.txt")))
            if (!line.isBlank() && !line.startsWith("#")) expected.add(line);
        assertEquals(expected, actual);
        assertEquals(404, request(PORTS.get("file"), "GET", "/api/contracts", null, auth()).statusCode());
        if (nacosConfig != null) verifyNacosRegistrationAndConfiguration();
    }

    @Test @Order(2) void gatewayRoutesAllProtectedLegacyEndpointsAndKeepsInternalApisPrivate() throws Exception {
        for (String line : Files.readAllLines(root.resolve("deploy/smoke-tests/src/test/resources/architecture/http-api-baseline.txt"))) {
            if (!line.startsWith("{")) continue;
            String path = line.substring(line.indexOf('[') + 1, line.indexOf(']')).replaceAll("\\{[^}]+}", "123");
            if (path.equals("/tcb_probe") || path.startsWith("/api/dev/") || path.equals("/api/auth/wechat-login")
                    || path.equals("/api/company-certifications/provider-callback") || path.equals("/api/fadada/callback")) continue;
            String method = line.substring(1, line.indexOf(' '));
            String type = line.contains("consumes [multipart/form-data]") ? "multipart/form-data; boundary=smoke" : "application/json";
            var result = request(PORTS.get("gateway"), method, path, "", Map.of("Content-Type", type));
            assertEquals(401, result.statusCode(), line + " -> " + result.body());
        }
        assertEquals(404, request(PORTS.get("gateway"), "POST", "/internal/identity/resolve", "", Map.of("X-TradePass-Internal-Key", KEY)).statusCode());
        assertEquals(401, request(PORTS.get("identity"), "POST", "/internal/identity/resolve", "", auth()).statusCode());
        assertEquals(401, request(PORTS.get("file"), "POST", "/internal/storage/get", "{}", Map.of()).statusCode());
        assertEquals(404, request(PORTS.get("gateway"), "GET", "/actuator/beans", null, Map.of()).statusCode());
        assertEquals(404, request(PORTS.get("gateway"), "GET", "/api/dev/users", null, Map.of()).statusCode());
    }

    @Test @Order(3) void remoteAuthenticationPreservesCompanySelectionAndRejectsSpoofing() throws Exception {
        var allowed = request(PORTS.get("gateway"), "GET", "/api/warehouses", null, auth());
        assertEquals(200, allowed.statusCode(), allowed.body());
        assertEquals(0, json(allowed).path("code").asInt(-1), allowed.body());
        Map<String, String> foreign = new HashMap<>(auth()); foreign.put("X-Company-Id", "1");
        assertEquals(403, request(PORTS.get("gateway"), "GET", "/api/warehouses", null, foreign).statusCode());
        assertEquals(401, request(PORTS.get("gateway"), "GET", "/api/warehouses", null,
                Map.of("x-wx-openid", "smoke-user", "X-TradePass-Internal-Key", KEY, "X-User-Id", "1")).statusCode());
        var management = request(MANAGEMENT.get("file"), "GET", "/actuator/beans", null, Map.of());
        assertFalse(management.body().contains("HikariDataSource"), "File process must not connect to MySQL");
        assertFalse(management.body().contains("MapperFactoryBean"), "File process must not own business mappers");
        assertEquals(200, request(MANAGEMENT.get("trade"), "GET", "/actuator/prometheus", null, Map.of()).statusCode());
        for (String role : ROLES) assertEquals(200, request(MANAGEMENT.get(role), "GET", "/actuator/health/readiness", null, Map.of()).statusCode(), role);
    }

    @Test @Order(40) void receiptInventoryAndReconciliationRemainAtomicAndIdempotent() throws Exception {
        var first = receive(DOCUMENT);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(0, json(first).path("code").asInt(-1), first.body());
        assertEquals("INBOUNDED", jdbc.queryForObject("SELECT status FROM business_document WHERE id=?", String.class, DOCUMENT));
        assertEquals(1, count("sales_order_receipt", DOCUMENT));
        assertEquals(1, count("inventory_transaction", DOCUMENT));
        assertEquals(1, count("reconciliation_entry", DOCUMENT));
        assertEquals(0, new java.math.BigDecimal("10").compareTo(jdbc.queryForObject("SELECT SUM(quantity) FROM inventory_balance WHERE warehouse_id=?", java.math.BigDecimal.class, WAREHOUSE)));
        var repeated = receive(DOCUMENT);
        assertEquals(200, repeated.statusCode(), repeated.body());
        assertEquals(1, count("sales_order_receipt", DOCUMENT));
        assertEquals(1, count("inventory_transaction", DOCUMENT));
        assertEquals(1, count("reconciliation_entry", DOCUMENT));
        var account = request(PORTS.get("gateway"), "GET", "/api/reconciliation-accounts", null, auth());
        assertEquals(200, account.statusCode(), account.body());
        assertEquals(0, json(account).path("code").asInt(-1), account.body());
    }

    @Test @Order(41) void settlementBranchFailureRollsBackTradeBranch() throws Exception {
        long id = DOCUMENT + 200;
        document(id);
        // Fault injection is test-administrator work; service credentials remain restricted.
        JdbcTemplate settlement = new JdbcTemplate(new DriverManagerDataSource(DATABASE_URLS.get("settlement"), username, password));
        settlement.execute("CREATE TRIGGER smoke_reject_entry BEFORE INSERT ON reconciliation_entry FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated downstream failure'");
        try {
            var failed = receive(id);
            assertTrue(failed.statusCode() >= 400, failed.body());
            assertEquals("ISSUED", jdbc.queryForObject("SELECT status FROM business_document WHERE id=?", String.class, id));
            assertEquals(0, count("sales_order_receipt", id));
            assertEquals(0, count("inventory_transaction", id));
            assertEquals(0, count("reconciliation_entry", id));
        } finally { settlement.execute("DROP TRIGGER smoke_reject_entry"); }
    }

    @Test @Order(42) void laterTradeFailureUndoesAnAlreadyCommittedContractBranch() throws Exception {
        long contract = CONTRACT + 400;
        OWNED.get("identity").update("INSERT INTO company_member(id,company_id,user_id,role_code,is_legal_person,is_administrator,status) VALUES (?,?,?,'LEGAL',1,1,'ACTIVE')", BASE + 10, SUPPLIER, USER);
        OWNED.get("contract").update("INSERT INTO trade_contract(id,company_id,counterparty_company_id,counterparty_name,name,amount,status,initiated_by,direction,contract_no) VALUES (?,?,?,'测试需方','分布式回滚合同',20,'ACTIVE',?,'SALE',?)", contract, SUPPLIER, BUYER, USER, "TX" + contract);
        var supplier = Map.of("Authorization", "Bearer " + TOKEN, "X-Company-Id", Long.toString(SUPPLIER));
        var applied = request(PORTS.get("gateway"), "POST", "/api/bilateral-actions", JSON.writeValueAsString(
                Map.of("bizType", "CONTRACT", "bizId", Long.toString(contract), "actionType", "VOID", "reason", "跨库回滚验证", "riskConfirmed", true)), supplier);
        assertEquals(200, applied.statusCode(), applied.body());
        long action = json(applied).path("data").path("id").asLong();
        assertTrue(action > 0, applied.body());
        JdbcTemplate administrator = new JdbcTemplate(new DriverManagerDataSource(DATABASE_URLS.get("trade"), username, password));
        administrator.execute("CREATE TRIGGER smoke_reject_result BEFORE INSERT ON approval_result_notification FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='failure after contract branch commit'");
        try {
            var failed = request(PORTS.get("gateway"), "POST", "/api/bilateral-actions/" + action + "/decision", "{\"decision\":\"APPROVE\"}", auth());
            assertTrue(failed.statusCode() >= 400, failed.body());
            assertEquals("ACTIVE", OWNED.get("contract").queryForObject("SELECT status FROM trade_contract WHERE id=?", String.class, contract));
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM bilateral_action_request WHERE id=?", String.class, action));
        } finally { administrator.execute("DROP TRIGGER smoke_reject_result"); }
        var success = request(PORTS.get("gateway"), "POST", "/api/bilateral-actions/" + action + "/decision", "{\"decision\":\"APPROVE\"}", auth());
        assertEquals(200, success.statusCode(), success.body());
        assertEquals("VOIDED", OWNED.get("contract").queryForObject("SELECT status FROM trade_contract WHERE id=?", String.class, contract));
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM bilateral_action_request WHERE id=?", String.class, action));
    }

    @Test @Order(43) void projectReadModelsPreserveBuyerDirectionAndTotalsAcrossDatabases() throws Exception {
        String counterparty = java.net.URLEncoder.encode("微服务企业" + SUPPLIER, java.nio.charset.StandardCharsets.UTF_8);
        JsonNode contracts = data("GET", "/api/contracts?status=ACTIVE&counterpartyName=" + counterparty, null);
        assertTrue(java.util.stream.StreamSupport.stream(contracts.path("items").spliterator(), false)
                .anyMatch(contract -> contract.path("id").asLong() == CONTRACT));
        assertEquals(0, data("GET", "/api/contracts?counterpartyName=missing-company", null).path("total").asInt());
        JsonNode created = data("POST", "/api/project-ledgers", "{\"name\":\"分库项目\",\"description\":\"兼容验证\"}");
        long project = created.path("id").asLong();
        assertTrue(project > 0);
        JsonNode available = data("GET", "/api/project-ledgers/" + project + "/available-contracts", null);
        assertTrue(java.util.stream.StreamSupport.stream(available.spliterator(), false).anyMatch(value -> value.path("id").asLong() == CONTRACT));
        data("POST", "/api/project-ledgers/" + project + "/contracts", "{\"contractIds\":[\"" + CONTRACT + "\"]}");
        JsonNode detail = data("GET", "/api/project-ledgers/" + project, null);
        assertEquals(1, detail.path("contractCount").asInt());
        assertEquals(0, new java.math.BigDecimal("20.00").compareTo(detail.path("purchaseCost").decimalValue()));
        assertEquals(0, new java.math.BigDecimal("-20.00").compareTo(detail.path("estimatedProfit").decimalValue()));
        assertEquals("PURCHASE", detail.path("contracts").get(0).path("direction").asText());
        assertEquals("微服务企业" + SUPPLIER, detail.path("contracts").get(0).path("counterpartyName").asText());
        assertTrue(data("GET", "/api/project-ledgers/contracts/" + CONTRACT + "/assignment", null).path("assigned").asBoolean());
        data("POST", "/api/project-ledgers/" + project + "/contracts/" + CONTRACT + "/remove", "{}");
        assertEquals(0, data("GET", "/api/project-ledgers/" + project, null).path("contractCount").asInt());
    }

    @Test @Order(44) void approvalQueriesKeepRemoteNamesAmountsAndDeletedItemFiltering() throws Exception {
        long sales = DOCUMENT + 500, returns = DOCUMENT + 600;
        document(sales); document(returns);
        jdbc.update("UPDATE business_document SET document_type='RETURN_ORDER' WHERE id=?", returns);
        var settlement = OWNED.get("settlement");
        settlement.update("UPDATE contract_attachment SET status='PENDING_CONFIRMATION',recipient_company_id=? WHERE id=?", BUYER, BASE + 80);
        try {
            JsonNode items = data("GET", "/api/approvals/fulfillment", null);
            Map<Long, JsonNode> byId = new HashMap<>(); items.forEach(item -> byId.put(item.path("id").asLong(), item));
            assertEquals("销售单", byId.get(sales).path("typeText").asText());
            assertEquals("退货单", byId.get(returns).path("typeText").asText());
            JsonNode payment = byId.get(BASE + 80);
            assertEquals("PAYMENT_VOUCHER", payment.path("approvalType").asText());
            assertEquals("微服务企业" + SUPPLIER, payment.path("sourceCompanyName").asText());
            assertEquals("MS" + BASE, payment.path("contractNo").asText());
            assertEquals(0, new java.math.BigDecimal("12345678901234.23").compareTo(payment.path("amount").decimalValue()));
            assertTrue(data("GET", "/api/approvals/summary", null).path("pendingFulfillmentCount").asInt() >= 3);
            jdbc.update("UPDATE business_document SET deleted_at=NOW() WHERE id=?", sales);
            JsonNode filtered = data("GET", "/api/approvals/fulfillment", null);
            assertFalse(java.util.stream.StreamSupport.stream(filtered.spliterator(), false).anyMatch(item -> item.path("id").asLong() == sales));
        } finally {
            settlement.update("UPDATE contract_attachment SET status='VOIDED' WHERE id=?", BASE + 80);
            jdbc.update("DELETE FROM business_document_item WHERE document_id IN (?,?)", sales, returns);
            jdbc.update("DELETE FROM business_document WHERE id IN (?,?)", sales, returns);
        }
    }

    @Test @Order(45) void reconciliationNamesContractNumbersAndWorkbookRemainConsistent() throws Exception {
        OWNED.get("identity").update("INSERT INTO counterparty_relation(id,company_id,counterparty_company_id,counterparty_company_name,relation_type,status) VALUES (?,?,?,?,'SUPPLIER','ACTIVE')", BASE + 11, BUYER, SUPPLIER, "微服务企业" + SUPPLIER);
        JsonNode accounts = data("GET", "/api/reconciliation-accounts", null);
        assertEquals(1, accounts.size());
        assertEquals("微服务企业" + SUPPLIER, accounts.get(0).path("counterpartyName").asText());
        JsonNode account = data("GET", "/api/reconciliation-accounts/" + SUPPLIER, null);
        assertEquals(0, new java.math.BigDecimal("20.00").compareTo(account.path("myPurchaseAmount").decimalValue()));
        assertEquals("MS" + BASE, account.path("entries").get(0).path("contractNo").asText());
        JsonNode workbook = data("GET", "/api/reconciliation-accounts/" + SUPPLIER + "/workbook-data", null);
        byte[] zip = Base64.getDecoder().decode(workbook.path("contentBase64").asText());
        StringBuilder xml = new StringBuilder();
        try (var entries = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (var entry = entries.getNextEntry(); entry != null; entry = entries.getNextEntry()) {
                if (entry.getName().endsWith(".xml")) xml.append(new String(entries.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        assertTrue(xml.toString().contains("MS" + BASE));
        assertTrue(xml.toString().contains("微服务企业" + SUPPLIER));
        assertTrue(xml.toString().contains("微服务企业" + BUYER));
    }

    static JsonNode data(String method, String path, String body) throws Exception {
        var response = request(PORTS.get("gateway"), method, path, body, auth());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(0, json(response).path("code").asInt(-1), response.body());
        return json(response).path("data");
    }

    @Test @Order(50) void fileServiceFailureRollsBackReceiptInventoryAndReconciliation() throws Exception {
        // Nacos/Sentinel clients drain during shutdown. Match the 60s container grace period.
        dumpCoverage("trade");
        PROCESSES.get("trade").destroy(); assertTrue(PROCESSES.get("trade").waitFor(60, TimeUnit.SECONDS));
        launch("trade", true); ready("trade");
        document(DOCUMENT + 100);
        var failure = receive(DOCUMENT + 100);
        assertEquals(400, failure.statusCode(), failure.body());
        assertEquals("ISSUED", jdbc.queryForObject("SELECT status FROM business_document WHERE id=?", String.class, DOCUMENT + 100));
        assertEquals(0, count("sales_order_receipt", DOCUMENT + 100));
        assertEquals(0, count("inventory_transaction", DOCUMENT + 100));
        assertEquals(0, count("reconciliation_entry", DOCUMENT + 100));
        assertEquals(0, new java.math.BigDecimal("10").compareTo(jdbc.queryForObject("SELECT SUM(quantity) FROM inventory_balance WHERE warehouse_id=?", java.math.BigDecimal.class, WAREHOUSE)));
    }

    @Test @Order(60) void logoutRevokesTheSameSessionAcrossServiceProcesses() throws Exception {
        var response = request(PORTS.get("gateway"), "POST", "/api/auth/logout", "", auth());
        assertEquals(200, response.statusCode(), response.body());
        for (String path : List.of("/api/warehouses", "/api/contracts", "/api/reconciliation-accounts"))
            assertEquals(401, request(PORTS.get("gateway"), "GET", path, null, auth()).statusCode());
    }

    @Test @Order(70) void identityOutageFailsClosedWithoutStoppingOtherProcesses() throws Exception {
        dumpCoverage("identity");
        PROCESSES.get("identity").destroyForcibly(); assertTrue(PROCESSES.get("identity").waitFor(10, TimeUnit.SECONDS));
        assertEquals(503, request(PORTS.get("gateway"), "GET", "/api/warehouses", null, auth()).statusCode());
        for (String role : List.of("trade", "contract", "settlement", "file")) {
            assertTrue(PROCESSES.get(role).isAlive());
            assertEquals(200, request(PORTS.get(role), "GET", "/tcb_probe", null, Map.of()).statusCode());
        }
    }

    static Map<String, String> auth() { return Map.of("Authorization", "Bearer " + TOKEN, "X-Company-Id", "" + BUYER); }
    static void dumpCoverage(String role) throws java.io.IOException {
        var client = new org.jacoco.core.tools.ExecDumpClient();
        client.setRetryCount(2);
        client.dump("127.0.0.1", COVERAGE.get(role)).save(Path.of("target", role + "-process.exec").toFile(), true);
    }
    static HttpResponse<String> receive(long document) throws Exception {
        String body = JSON.writeValueAsString(Map.of("decision", "INBOUND", "warehouseId", "" + WAREHOUSE,
                "signatureBase64", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aHd0AAAAASUVORK5CYII="));
        return request(PORTS.get("gateway"), "POST", "/api/trade-documents/" + document + "/receive", body, auth());
    }
    static int count(String table, long document) {
        return switch (table) {
            case "inventory_transaction" -> jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction t JOIN inventory_inbound i ON t.biz_id=i.id WHERE t.biz_type='SALES_ORDER_INBOUND' AND i.source_document_id=?", Integer.class, document);
            case "reconciliation_entry" -> OWNED.get("settlement").queryForObject("SELECT COUNT(*) FROM reconciliation_entry WHERE source_id=?", Integer.class, document);
            case "sales_order_receipt" -> jdbc.queryForObject("SELECT COUNT(*) FROM sales_order_receipt WHERE document_id=?", Integer.class, document);
            default -> throw new IllegalArgumentException(table);
        };
    }
    static JsonNode json(HttpResponse<String> response) throws Exception { return JSON.readTree(response.body()); }
    static HttpResponse<String> request(int port, String method, String path, String body, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(40));
        headers.forEach(builder::header);
        if (!headers.containsKey("Content-Type")) builder.header("Content-Type", "application/json");
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static void seed() throws Exception {
        jdbc.update("INSERT INTO sys_user(id,openid,nickname) VALUES (?,?,?)", USER, "smoke-" + BASE, "微服务测试");
        for (long company : List.of(SUPPLIER, BUYER)) jdbc.update("INSERT INTO company(id,name,credit_code,legal_person_name) VALUES (?,?,?,?)", company, "微服务企业" + company, "S" + company, "测试法人");
        jdbc.update("INSERT INTO company_member(id,company_id,user_id,role_code,is_legal_person,is_administrator,status) VALUES (?,?,?,'LEGAL',1,1,'ACTIVE')", BASE + 9, BUYER, USER);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TOKEN.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO auth_session(token_hash,user_id,expires_at) VALUES (?,?,DATE_ADD(NOW(),INTERVAL 1 DAY))", hash, USER);
        jdbc.update("INSERT INTO trade_contract(id,company_id,counterparty_company_id,counterparty_name,name,amount,status,initiated_by,direction,contract_no) VALUES (?,?,?,'测试需方','微服务回归',20,'ACTIVE',?,'SALE',?)", CONTRACT, SUPPLIER, BUYER, USER, "MS" + BASE);
        jdbc.update("INSERT INTO warehouse(id,company_id,name,created_by) VALUES (?,?,?,?)", WAREHOUSE, BUYER, "微服务测试仓" + BASE, USER);
        document(DOCUMENT);
        byte[] file = new byte[]{0, 1, (byte) 255, 10};
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file));
        jdbc.update("INSERT INTO contract_attachment(id,contract_id,uploader_company_id,category,original_name,content_type,file_size,file_data,sha256,voucher_amount,created_by,status) VALUES (?,?,?,'PAYMENT_VOUCHER','迁移样本.bin','application/octet-stream',4,?,?,12345678901234.23,?,'VOIDED')",
                BASE + 80, CONTRACT, SUPPLIER, file, checksum, USER);
        int offset = 90;
        for (String type : List.of("HISTORICAL_UNKNOWN", "CONTRACT", "BUSINESS_DOCUMENT", "CONTRACT_ATTACHMENT")) {
            jdbc.update("INSERT INTO audit_log(id,company_id,user_id,biz_type,biz_id,action,detail) VALUES (?,?,?,?,?,'MIGRATION_FIXTURE','保留历史审计')",
                    BASE + offset++, BUYER, USER, type, Long.toString(CONTRACT));
        }
    }
    static void document(long id) {
        String content = "{\"columns\":[\"品名\",\"规格\",\"单位\",\"数量\",\"单价\",\"金额\"],\"rows\":[[\"微服务测试商品\",\"A\",\"件\",\"10\",\"2\",\"20\"]]}";
        jdbc.update("INSERT INTO business_document(id,company_id,recipient_company_id,contract_id,document_type,document_no,template_id,template_name,content,created_by,status,supplier_company_id,buyer_company_id) VALUES (?,?,?,?,'SALES_ORDER',?,0,'测试模板',?,?,'ISSUED',?,?)", id, SUPPLIER, BUYER, CONTRACT, "MS" + id, content, USER, SUPPLIER, BUYER);
        jdbc.update("INSERT INTO business_document_item(id,document_id,issuer_company_id,recipient_company_id,line_no,product_name,specification,base_unit,quantity,unit_price,amount) VALUES (?,?,?,?,1,'微服务测试商品','A','件',10,2,20)", id + 1000, id, SUPPLIER, BUYER);
    }

    static void configureNacos() throws Exception {
        String server = System.getProperty("tradepass.test.nacos.server");
        if (server == null || server.isBlank()) return;
        // Restrict automatic test configuration writes to a local test server and an ephemeral group.
        assertTrue(server.matches("(127\\.0\\.0\\.1|localhost):[0-9]+"), "Local Nacos test server required");
        nacosGroup = "TRADEPASS_VALIDATION_" + UUID.randomUUID().toString().replace("-", "");
        var props = new Properties(); props.setProperty("serverAddr", server);
        nacosConfig = com.alibaba.nacos.api.NacosFactory.createConfigService(props);
        nacosNaming = com.alibaba.nacos.api.NacosFactory.createNamingService(props);
        publishConfig("tradepass-common.yaml", "management:\n  metrics:\n    tags:\n      config_probe: " + nacosGroup + "\n", "yaml");
        for (String role : ROLES) {
            publishConfig("tradepass-" + role + ".yaml", "tradepass:\n  config-probe: " + role + "\n", "yaml");
            publishConfig("tradepass-" + role + "-flow.json", "[]", "json");
            if (!role.equals("gateway")) publishConfig("tradepass-" + role + "-degrade.json", "[]", "json");
        }
    }

    static void publishConfig(String id, String content, String type) throws Exception {
        if (!NACOS_DATA_IDS.contains(id)) NACOS_DATA_IDS.add(id);
        assertTrue(nacosConfig.publishConfig(id, nacosGroup, content, type));
    }

    static void verifyNacosRegistrationAndConfiguration() throws Exception {
        for (String role : ROLES) {
            var instances = nacosNaming.getAllInstances("tradepass-" + role, nacosGroup);
            assertTrue(instances.stream().anyMatch(instance -> instance.isHealthy() && instance.getPort() == PORTS.get(role)), role);
            var metrics = request(MANAGEMENT.get(role), "GET", "/actuator/prometheus", null, Map.of());
            assertTrue(metrics.body().contains("config_probe=\"" + nacosGroup + "\""), "Nacos config not imported by " + role);
        }
        publishConfig("tradepass-gateway-flow.json", "[{\"resource\":\"identity\",\"count\":0,\"intervalSec\":1}]", "json");
        awaitStatus("/api/me", 429);
        publishConfig("tradepass-gateway-flow.json", "[]", "json");
        awaitStatus("/api/me", 401);
    }

    static void awaitStatus(String path, int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        int actual = -1;
        while (System.nanoTime() < deadline) {
            actual = request(PORTS.get("gateway"), "GET", path, null, Map.of()).statusCode();
            if (actual == expected) return;
            Thread.sleep(200);
        }
        assertEquals(expected, actual, "Nacos Sentinel rule did not take effect");
    }
}
