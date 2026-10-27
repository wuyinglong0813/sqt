package com.tradepass.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentMapper;
import com.tradepass.module.trade.service.inventory.SalesOrderInventoryServiceImpl;
import com.tradepass.module.trade.service.inventory.SalesOrderSignatureService;
import com.tradepass.support.RepoRoot;
import com.tradepass.support.TestIds;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlInventoryRemarkTest {
    @Test
    void overviewReturnsLatestNonblankActiveInboundRemarkForEachCompanyWarehouseAndProduct() {
        String base = System.getProperty("tradepass.test.mysql.url");
        if (!base.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only isolated tradepass_fix_validation_* databases are allowed");
        String user = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, user, password));
        String database = "tradepass_fix_validation_remark_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        try {
            var source = new DriverManagerDataSource(base.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + database), user, password);
            var jdbc = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("filesystem:" + RepoRoot.find()
                    .resolve("tradepass-business/src/main/resources/db/business").toAbsolutePath()).load().migrate();
            jdbc.update("INSERT INTO warehouse(id,company_id,name,created_by) VALUES(9,4,'一号仓',8),(10,4,'二号仓',8),(19,5,'其他企业仓',18)");
            jdbc.update("""
                    INSERT INTO inventory_product(id,company_id,product_name,model,specification,base_unit)
                    VALUES(77,4,'电缆','YJV','3×25','米'),(78,4,'电缆','YJLV','3×25','米'),
                          (79,4,'阀门','','DN50','件'),(177,5,'电缆','YJV','3×25','米')
                    """);
            var service = new SalesOrderInventoryServiceImpl(jdbc, mock(BusinessDocumentMapper.class),
                    mock(ContractReader.class), mock(AccessControlOperations.class), mock(AuditLogService.class),
                    new ObjectMapper(), null, mock(SalesOrderSignatureService.class), mock(UserIdentityOperations.class));
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            AuthContext.set(8L, 4L);
            TestIds.use(LongStream.range(100, 120).toArray());
            tx.executeWithoutResult(status -> {
                service.manualInbound(9L, "manual-remark-test-a", "电缆", "YJV", "3×25", "米", BigDecimal.ONE, BigDecimal.TEN, "  期初库存  ");
                service.manualInbound(9L, "manual-remark-test-a", "电缆", "YJV", "3×25", "米", BigDecimal.ONE, BigDecimal.TEN, "重试不覆盖原备注");
                service.manualInbound(10L, "manual-remark-test-b", "电缆", "YJV", "3×25", "米", BigDecimal.ONE, BigDecimal.TEN, "二号仓专用");
                service.manualInbound(9L, "manual-remark-test-c", "电缆", "YJLV", "3×25", "米", BigDecimal.ONE, BigDecimal.TEN, "铝芯电缆");
                service.manualInbound(9L, "manual-remark-test-d", "阀门", "", "DN50", "件", BigDecimal.ONE, BigDecimal.TEN, "");
            });
            jdbc.update("UPDATE inventory_manual_entry SET created_at='2026-01-01 00:00:00'");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_manual_entry", Long.class)).isEqualTo(4);
            assertThat(remark(service, 9, 77)).isEqualTo("期初库存");
            assertThat(remark(service, 10, 77)).isEqualTo("二号仓专用");
            assertThat(remark(service, 9, 78)).isEqualTo("铝芯电缆");
            assertThat(remark(service, 9, 79)).isEmpty();

            // Pre-existing manual entries are visible without a migration, and a later
            // blank entry does not hide the last meaningful note.
            manualEntry(jdbc, 201, 4, 9, 77, "复检后入库", "2026-01-02 00:00:00");
            manualEntry(jdbc, 202, 4, 9, 77, "   ", "2026-01-03 00:00:00");
            assertThat(remark(service, 9, 77)).isEqualTo("复检后入库");
            inbound(jdbc, 301, 401, 77, "第一行备注", "2026-01-04 00:00:00", "COMPLETED");
            documentItem(jdbc, 402, 301, 2, " 项目A专用\n请勿混放 ");
            jdbc.update("INSERT INTO inventory_inbound_item(id,inbound_id,document_item_id,product_id,quantity) VALUES(402,301,402,77,1)");
            assertThat(remark(service, 9, 77)).isEqualTo(" 项目A专用\n请勿混放 ");
            inbound(jdbc, 302, 403, 77, "同秒后一次单据入库", "2026-01-04 00:00:00", "COMPLETED");
            assertThat(remark(service, 9, 77)).isEqualTo("同秒后一次单据入库");
            manualEntry(jdbc, 400, 4, 9, 77, "同秒后一次手动入库", "2026-01-04 00:00:00");
            assertThat(remark(service, 9, 77)).isEqualTo("同秒后一次手动入库");

            // Sales inbounds mark inventory_inbound REVERSED; return-order reversal
            // marks inventory_transfer instead. Neither can supply the visible note.
            inbound(jdbc, 501, 601, 77, "已作废销售入库", "2026-01-05 00:00:00", "REVERSED");
            inbound(jdbc, 502, 602, 77, "已作废退货入库", "2026-01-06 00:00:00", "COMPLETED");
            jdbc.update("""
                    INSERT INTO inventory_transfer(id,source_document_id,outbound_company_id,outbound_warehouse_id,
                        inbound_company_id,inbound_warehouse_id,status,created_by)
                    VALUES(701,502,5,19,4,9,'REVERSED',8)
                    """);
            inbound(jdbc, 503, 603, 77, null, "2026-01-07 00:00:00", "COMPLETED");
            assertThat(remark(service, 9, 77)).isEqualTo("同秒后一次手动入库");
            jdbc.update("UPDATE inventory_manual_entry SET remark=' ' WHERE id=400");
            assertThat(remark(service, 9, 77)).isEqualTo("同秒后一次单据入库");

            manualEntry(jdbc, 801, 5, 19, 177, "其他企业备注", "2026-12-31 00:00:00");
            jdbc.update("INSERT INTO inventory_balance(id,company_id,warehouse_id,product_id,quantity) VALUES(802,5,19,177,1)");
            assertThat(balances(service)).hasSize(4).allSatisfy(item -> assertThat(item.get("warehouseId")).isIn(9L, 10L));
            assertThat(remark(service, 9, 77)).isEqualTo("同秒后一次单据入库");
            assertThat(remark(service, 10, 77)).isEqualTo("二号仓专用");
            assertThat(remark(service, 9, 78)).isEqualTo("铝芯电缆");
            assertThat(remark(service, 9, 79)).isEmpty();
        } finally {
            AuthContext.clear();
            TestIds.reset();
            admin.execute("DROP DATABASE `" + database + "`");
        }
    }

    private static void manualEntry(JdbcTemplate jdbc, long id, long companyId, long warehouseId,
                                    long productId, String remark, String createdAt) {
        jdbc.update("""
                INSERT INTO inventory_manual_entry(id,company_id,request_id,warehouse_id,product_id,
                    quantity,unit_price,amount,remark,created_by,created_at)
                VALUES(?,?,?,?,?,1,10,10,?,8,?)
                """, id, companyId, "historical-remark-" + id, warehouseId, productId, remark, createdAt);
    }

    private static void inbound(JdbcTemplate jdbc, long id, long itemId, long productId,
                                String remark, String createdAt, String status) {
        jdbc.update("""
                INSERT INTO inventory_inbound(id,company_id,warehouse_id,source_document_id,
                    inbound_no,status,created_by,created_at)
                VALUES(?,4,9,?,?,?,?,?)
                """, id, id, "IN-REMARK-" + id, status, 8, createdAt);
        documentItem(jdbc, itemId, id, 1, remark);
        jdbc.update("""
                INSERT INTO inventory_inbound_item(id,inbound_id,document_item_id,product_id,quantity)
                VALUES(?,?,?,?,1)
                """, itemId, id, itemId, productId);
    }

    private static void documentItem(JdbcTemplate jdbc, long id, long documentId, int lineNo, String remark) {
        jdbc.update("""
                INSERT INTO business_document_item(id,document_id,issuer_company_id,recipient_company_id,
                    line_no,product_name,model,specification,base_unit,quantity,unit_price,amount,remark)
                VALUES(?,?,5,4,?,'电缆','YJV','3×25','米',1,10,10,?)
                """, id, documentId, lineNo, remark);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> balances(SalesOrderInventoryServiceImpl service) {
        return (List<Map<String, Object>>) service.inventoryOverview().get("balances");
    }

    private static String remark(SalesOrderInventoryServiceImpl service, long warehouseId, long productId) {
        return (String) balances(service).stream()
                .filter(item -> Long.valueOf(warehouseId).equals(item.get("warehouseId"))
                        && Long.valueOf(productId).equals(item.get("productId")))
                .findFirst().orElseThrow().get("remark");
    }
}
