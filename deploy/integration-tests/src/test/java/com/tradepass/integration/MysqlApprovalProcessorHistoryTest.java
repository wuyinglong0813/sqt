package com.tradepass.integration;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.trade.service.approval.ApprovalServiceImpl;
import com.tradepass.support.RepoRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlApprovalProcessorHistoryTest {
    @ParameterizedTest
    @ValueSource(strings = {"flat", "trade", "business"})
    void upgradesKeepBothCompaniesHistoryWithoutMirroringAgain(String role) throws Exception {
        String url = System.getProperty("tradepass.test.mysql.url");
        if (!url.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only isolated tradepass_fix_validation_* databases are allowed");
        String username = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        String database = "tradepass_fix_validation_approval_" + role + "_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        try {
            var source = new DriverManagerDataSource(url.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + database), username, password);
            var jdbc = new JdbcTemplate(source);
            String path = switch (role) {
                case "flat" -> "sql/mysql";
                case "trade" -> "tradepass-module-trade/tradepass-module-trade-server/src/main/resources/db/owned/trade";
                default -> "tradepass-business/src/main/resources/db/business";
            };
            String previous = switch (role) { case "flat" -> "49"; case "trade" -> "4"; default -> "11"; };
            String location = "filesystem:" + RepoRoot.find().resolve(path).toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target(previous).load().migrate();
            jdbc.update("""
                    INSERT INTO approval_result_notification
                        (id,recipient_company_id,source_company_id,result_type,source_id,contract_id,
                         result_status,title,detail,rejected_reason,created_at)
                    VALUES (101,3,4,'SALES_ORDER',31,12,'APPROVED','销售单已确认','对方已确认销售单 XS-31',NULL,'2026-10-28 20:01:00'),
                           (102,3,4,'SALES_ORDER',31,12,'INBOUNDED','销售单已入库','对方已入库销售单 XS-31',NULL,'2026-10-28 20:02:00'),
                           (103,3,4,'RETURN_ORDER',32,12,'REJECTED','退货单已驳回','对方已驳回退货单 TH-32','数量不一致','2026-10-28 20:03:00'),
                           (104,3,4,'INVOICE',33,12,'APPROVED','发票已通过','对方已确认发票 invoice.pdf',NULL,'2026-10-28 20:04:00'),
                           (105,3,4,'PAYMENT_VOUCHER',34,12,'REJECTED','凭证已驳回','对方已驳回转款凭证 payment.pdf','金额不一致','2026-10-28 20:05:00'),
                           (106,3,4,'BILATERAL_ACTION',35,12,'APPROVED','结束申请已同意','对方已同意结束申请',NULL,'2026-10-28 20:06:00'),
                           (107,3,4,'CONTRACT',12,12,'APPROVED','合同已签署','对方已签署合同 HT-12',NULL,'2026-10-28 20:07:00'),
                           (108,4,3,'INVOICE',33,12,'APPROVED','已同意发票','我方已同意发票 invoice.pdf',NULL,'2026-10-28 20:04:00')
                    """);
            var flyway = Flyway.configure().dataSource(source).locations(location).load();
            flyway.migrate();
            flyway.validate();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_result_notification", Long.class)).isEqualTo(13L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_result_notification WHERE id < 0 AND read_at IS NOT NULL", Long.class)).isEqualTo(5L);
            assertThat(jdbc.queryForObject("SELECT rejected_reason FROM approval_result_notification WHERE id=-103", String.class)).isEqualTo("数量不一致");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_result_notification WHERE id IN (-104,-107)", Long.class)).isZero();

            String backfill = Files.readString(RepoRoot.find().resolve("sql/mysql/V50__approval_processor_history.sql"));
            jdbc.execute(backfill);
            jdbc.execute(backfill);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_result_notification", Long.class)).isEqualTo(13L);

            var directory = mock(IdentityDirectoryOperations.class);
            when(directory.companyNames(anyList())).thenReturn(Map.of(3L, "供方", 4L, "需方"));
            var approvals = new ApprovalServiceImpl(jdbc, mock(AccessControlOperations.class));
            ReflectionTestUtils.setField(approvals, "identityDirectory", directory);
            ReflectionTestUtils.setField(approvals, "contractReader", mock(ContractReader.class));
            AuthContext.set(7L, 3L);
            assertThat(approvals.results()).hasSize(7).anySatisfy(item -> assertThat(item).containsEntry("sourceId", 31L).containsEntry("resultStatus", "APPROVED"));
            AuthContext.set(8L, 4L);
            assertThat(approvals.results()).hasSize(6).anySatisfy(item -> assertThat(item).containsEntry("sourceId", 31L).containsEntry("resultStatus", "APPROVED").containsEntry("isRead", true));
        } finally {
            AuthContext.clear();
            admin.execute("DROP DATABASE `" + database + "`");
        }
    }
}
