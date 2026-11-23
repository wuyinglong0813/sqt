package com.tradepass.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlTradeTaxPricingTest {
    @Test
    void confirmedInputTaxChangesStockCostWhileSettlementKeepsGrossAmounts() {
        String base = System.getProperty("tradepass.test.mysql.url");
        if (!base.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?")) {
            throw new IllegalArgumentException("Only isolated tradepass_fix_validation_* databases are allowed");
        }
        String user = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, user, password));
        String database = "tradepass_fix_validation_tax_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        try {
            String url = base.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + database);
            var source = new DriverManagerDataSource(url, user, password);
            var jdbc = new JdbcTemplate(source);
            String location = "filesystem:" + RepoRoot.find()
                    .resolve("tradepass-business/src/main/resources/db/business").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("13").load().migrate();
            var flyway = Flyway.configure().dataSource(source).locations(location).load();
            flyway.migrate();
            flyway.validate();
            jdbc.update("""
                    INSERT INTO warehouse(id,company_id,name,created_by)
                    VALUES(9,3,'甲方仓',7),(10,4,'主仓',8),(11,5,'乙方仓',9),(12,6,'客户仓',10)
                    """);
            var documents = mock(BusinessDocumentMapper.class);
            var contracts = mock(ContractReader.class);
            var accounts = mock(ReconciliationAccountOperations.class);
            var service = new SalesOrderInventoryServiceImpl(jdbc, documents, contracts,
                    mock(AccessControlOperations.class), mock(AuditLogService.class), new ObjectMapper(), accounts,
                    mock(SalesOrderSignatureService.class), mock(UserIdentityOperations.class));
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            TestIds.use(LongStream.range(1000, 1300).toArray());

            var first = salesOrder(31L, 3L, 4L, 9L, "GROSS", "100", "113", "10000.00", "1300.00", "11300.00");
            var second = salesOrder(32L, 5L, 4L, 11L, "NET", "100", "120", "12000.00", "1560.00", "13560.00");
            var sale = salesOrder(33L, 4L, 6L, 10L, "NET", "50", "150", "7500.00", "975.00", "8475.00");
            for (BusinessDocumentDO document : List.of(first, second, sale)) {
                var contract = new TradeContractRespDTO();
                contract.setId(document.getContractId());
                contract.setCompanyId(document.getCompanyId());
                contract.setCounterpartyCompanyId(document.getRecipientCompanyId());
                contract.setStatus("ACTIVE");
                contract.setDirection("SALE");
                when(contracts.selectById(contract.getId())).thenReturn(contract);
                when(contracts.selectByIdForUpdate(contract.getId())).thenReturn(contract);
                when(documents.selectById(document.getId())).thenReturn(document);
                when(documents.selectByIdForUpdate(document.getId())).thenReturn(document);
                transactions.executeWithoutResult(status -> service.saveDocumentItems(document));
            }

            AuthContext.set(8L, 4L);
            transactions.executeWithoutResult(status -> {
                receive(service, 31L, 10L, "1300.00");
                receive(service, 32L, 10L, "1560.00");
                receive(service, 32L, 10L, "1560.00");
            });
            assertThat(balance(jdbc, "quantity")).isEqualByComparingTo("200");
            assertThat(balance(jdbc, "unit_price")).isEqualByComparingTo("110");
            assertThat(balance(jdbc, "inventory_amount")).isEqualByComparingTo("22000.00");
            assertThat(jdbc.queryForList("""
                    SELECT item.amount FROM inventory_inbound_item item
                    JOIN inventory_inbound inbound ON inbound.id=item.inbound_id
                    WHERE inbound.company_id=4 ORDER BY inbound.source_document_id
                    """, BigDecimal.class)).containsExactly(new BigDecimal("10000.00"), new BigDecimal("12000.00"));
            verify(accounts).recordSalesOrder(any(), eq(new BigDecimal("11300.00")), any(), eq(8L), any());
            verify(accounts).recordSalesOrder(any(), eq(new BigDecimal("13560.00")), any(), eq(8L), any());

            AuthContext.set(10L, 6L);
            transactions.executeWithoutResult(status -> receive(service, 33L, 12L, "0.00"));
            assertThat(balance(jdbc, "quantity")).isEqualByComparingTo("150");
            assertThat(balance(jdbc, "inventory_amount")).isEqualByComparingTo("16500.00");
            assertThat(jdbc.queryForObject("""
                    SELECT cost_amount_delta FROM inventory_transaction
                    WHERE company_id=4 AND warehouse_id=10 AND biz_type='SALES_ORDER_OUTBOUND'
                    """, BigDecimal.class)).isEqualByComparingTo("-5500.00");
            verify(accounts).recordSalesOrder(any(), eq(new BigDecimal("8475.00")), any(), eq(10L), any());
        } finally {
            AuthContext.clear();
            TestIds.reset();
            admin.execute("DROP DATABASE `" + database + "`");
        }
    }

    private static void receive(SalesOrderInventoryServiceImpl service, long id, long warehouse, String deductible) {
        service.receive(id, "INBOUND", warehouse, null, null, null, """
                [{"rowIndex":0,"status":"CONFIRMED","deductibleAmount":"%s"}]
                """.formatted(deductible));
    }

    private static BigDecimal balance(JdbcTemplate jdbc, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM inventory_balance WHERE company_id=4 AND warehouse_id=10",
                BigDecimal.class);
    }

    private static BusinessDocumentDO salesOrder(long id, long supplier, long buyer, long warehouse,
                                                 String basis, String quantity, String unitPrice,
                                                 String net, String tax, String gross) {
        var document = new BusinessDocumentDO();
        document.setId(id);
        document.setDocumentNo("XS-TAX-" + id);
        document.setDocumentType("SALES_ORDER");
        document.setContractId(id + 100);
        document.setCompanyId(supplier);
        document.setSupplierCompanyId(supplier);
        document.setRecipientCompanyId(buyer);
        document.setBuyerCompanyId(buyer);
        document.setOutboundWarehouseId(warehouse);
        document.setStatus("ACKNOWLEDGED");
        document.setAcknowledgedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        document.setContent("""
                {"columns":["品名","规格","单位","数量","单价","金额"],
                 "rows":[["球阀","DN25","件","%s","%s","%s"]],
                 "taxPricing":{"version":1,"basis":"%s","netAmount":"%s","taxAmount":"%s","grossAmount":"%s",
                 "lines":[{"rowIndex":0,"lineType":"PRODUCT","lineId":"PRODUCT:0","sourceLineId":"PRODUCT:0",
                 "taxTreatment":"TAXABLE","taxRate":"13","quantity":"%s","quoteUnitPrice":"%s",
                 "netAmount":"%s","taxAmount":"%s","grossAmount":"%s"}]}}
                """.formatted(quantity, unitPrice, "GROSS".equals(basis) ? gross : net, basis, net, tax, gross,
                quantity, unitPrice, net, tax, gross));
        return document;
    }
}
