package com.tradepass.integration;

import com.tradepass.support.RepoRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlLocalContractVoidMigrationTest {
    @ParameterizedTest @ValueSource(strings = {"legacy", "business"})
    void approvedVoidsFinishLocallyWithoutAlteringProviderEvidence(String layout) {
        String url = System.getProperty("tradepass.test.mysql.url");
        if (!url.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?")) {
            throw new IllegalArgumentException("Only a dedicated tradepass_fix_validation_* database is allowed");
        }
        String user = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String database = "tradepass_fix_validation_void_" + layout + "_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        var source = new DriverManagerDataSource(
                url.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + database), user, password);
        var jdbc = new JdbcTemplate(source);
        String directory = layout.equals("legacy") ? "sql/mysql" : "tradepass-business/src/main/resources/db/business";
        String location = "filesystem:" + RepoRoot.find().resolve(directory).toAbsolutePath();
        try {
            Flyway.configure().dataSource(source).locations(location)
                    .target(layout.equals("legacy") ? "43" : "6").load().migrate();
            for (long id = 1; id <= 13; id++) {
                jdbc.update("""
                        INSERT INTO trade_contract(id,company_id,counterparty_company_id,counterparty_name,
                            contract_no,name,direction,status,version_no,initiated_by)
                        VALUES (?,3,4,'对方企业',?, '测试合同','SALE',?,1,7)
                        """, id, "VOID-MIGRATION-" + id, id == 7 ? "PENDING" : id == 8 ? "VOIDED" : "ACTIVE");
            }
            for (long id : List.of(1L, 5L, 6L, 7L, 8L, 11L, 12L, 13L)) action(jdbc, id, id, "CONTRACT", "VOID", "APPROVED");
            action(jdbc, 2, 2, "CONTRACT", "VOID", "PENDING");
            action(jdbc, 3, 3, "CONTRACT", "VOID", "REJECTED");
            action(jdbc, 4, 4, "CONTRACT", "VOID", "CANCELLED");
            action(jdbc, 14, 4, "CONTRACT", "RESUME", "APPROVED");
            action(jdbc, 15, 5, "CONTRACT", "RESUME", "PENDING");
            action(jdbc, 16, 6, "CONTRACT", "END", "PENDING");
            action(jdbc, 9, 9, "ATTACHMENT", "VOID", "APPROVED");
            action(jdbc, 10, 10, "CONTRACT", "VOID", "APPROVED");
            jdbc.update("UPDATE bilateral_action_request SET biz_id=999 WHERE id=10");
            jdbc.update("""
                    INSERT INTO business_document(id,company_id,contract_id,document_type,document_no,
                        template_id,template_name,status,created_by)
                    VALUES (111,3,11,'SALES_ORDER','PENDING-VOID-DOCUMENT',1,'测试模板','ACKNOWLEDGED',7)
                    """);
            for (long contract : List.of(12L, 13L)) {
                jdbc.update("""
                        INSERT INTO contract_attachment(id,contract_id,uploader_company_id,category,status,
                            original_name,content_type,file_size,sha256,created_by)
                        VALUES (?,?,3,?,'APPROVED','原资料.pdf','application/pdf',1,?,7)
                        """, 200 + contract, contract, contract == 12 ? "INVOICE" : "OTHER", "0".repeat(64));
            }

            jdbc.update("""
                    INSERT INTO fadada_contract_sign_task(id,contract_id,version_no,sign_task_id,provider_status,
                        initiator_company_id,counterparty_company_id,source_file_id)
                    VALUES (101,1,1,'signed-original','ABOLISH_CREATION_UNCERTAIN',3,4,'signed-file')
                    """);
            jdbc.update("""
                    INSERT INTO fadada_abolish_creation_intent(id,contract_id,version_no,original_sign_task_id)
                    VALUES ('old-intent',1,1,'signed-original')
                    """);

            var flyway = Flyway.configure().dataSource(source).locations(location)
                    .target(layout.equals("legacy") ? "44" : "7").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT id FROM trade_contract WHERE status='VOIDED' ORDER BY id", Long.class))
                    .containsExactly(1L, 5L, 8L, 13L);
            assertThat(jdbc.queryForObject("SELECT status FROM bilateral_action_request WHERE id=15", String.class))
                    .isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT decision_reason FROM bilateral_action_request WHERE id=15", String.class))
                    .contains("历史恢复申请已关闭");
            assertThat(jdbc.queryForObject("SELECT source_file_id FROM fadada_contract_sign_task WHERE id=101", String.class))
                    .isEqualTo("signed-file");
            assertThat(jdbc.queryForObject("SELECT provider_status FROM fadada_contract_sign_task WHERE id=101", String.class))
                    .isEqualTo("ABOLISH_CREATION_UNCERTAIN");
            assertThat(jdbc.queryForObject("SELECT status FROM fadada_abolish_creation_intent WHERE id='old-intent'", String.class))
                    .isEqualTo("UNCONFIRMED");
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            String migration = layout.equals("legacy") ? "V44__local_contract_void.sql" : "V7__local_contract_void.sql";
            new ResourceDatabasePopulator(new FileSystemResource(RepoRoot.find().resolve(directory).resolve(migration)))
                    .execute(source);
            assertThat(jdbc.queryForList("SELECT id FROM trade_contract WHERE status='VOIDED' ORDER BY id", Long.class))
                    .containsExactly(1L, 5L, 8L, 13L);
        } finally {
            admin.execute("DROP DATABASE `" + database + "`");
        }
    }

    private void action(JdbcTemplate jdbc, long id, long contract, String type, String action, String status) {
        jdbc.update("""
                INSERT INTO bilateral_action_request(id,contract_id,biz_type,biz_id,action_type,
                    requester_company_id,requester_user_id,approver_company_id,reason,status)
                VALUES (?,?,?,?,?,3,7,4,'双方确认',?)
                """, id, contract, type, contract, action, status);
    }
}
