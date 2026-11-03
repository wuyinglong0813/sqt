package com.tradepass.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
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
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlZeroInventoryTest {
    @Test
    void zeroEntriesPreserveStockValuesAndSalesAndReturnsKeepZeroBalancesVisible() {
        String base = System.getProperty("tradepass.test.mysql.url");
        if (!base.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only isolated tradepass_fix_validation_* databases are allowed");
        String user = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, user, password));
        String database = "tradepass_fix_validation_zero_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        try {
            var source = new DriverManagerDataSource(base.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + database), user, password);
            var jdbc = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).locations("filesystem:" + RepoRoot.find()
                    .resolve("tradepass-business/src/main/resources/db/business").toAbsolutePath()).load().migrate();
            jdbc.update("INSERT INTO warehouse(id,company_id,name,created_by) VALUES(9,3,'供方仓',7),(10,4,'需方仓',8)");
            var documents = mock(BusinessDocumentMapper.class);
            var contracts = mock(ContractReader.class);
            var service = new SalesOrderInventoryServiceImpl(jdbc, documents, contracts,
                    mock(AccessControlOperations.class), mock(AuditLogService.class), new ObjectMapper(), null,
                    mock(SalesOrderSignatureService.class), mock(UserIdentityOperations.class));
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            TestIds.use(LongStream.range(100, 300).toArray());
            AuthContext.set(7L, 3L);

            // A zero entry can establish a SKU, and a second zero entry must not divide by zero.
            tx.executeWithoutResult(status -> service.manualInbound(9L, "manual-zero-first", "电缆", "YJV", "3×25", "米",
                    BigDecimal.ZERO, BigDecimal.ZERO, "待补货"));
            long productId = jdbc.queryForObject("SELECT id FROM inventory_product WHERE company_id=3", Long.class);
            long balanceId = jdbc.queryForObject("SELECT id FROM inventory_balance WHERE product_id=?", Long.class, productId);
            tx.executeWithoutResult(status -> {
                service.manualInbound(9L, "manual-zero-second", "电缆", "YJV", "3×25", "米", BigDecimal.ZERO, BigDecimal.TEN, "");
                service.manualInbound(9L, "manual-zero-second", "电缆", "YJV", "3×25", "米", BigDecimal.ZERO, BigDecimal.TEN, "");
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_manual_entry", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction", Long.class)).isEqualTo(2);
            assertBalance(service, balanceId, productId, "0", "0", "0");
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.manualInbound(9L, "manual-negative-entry",
                    "电缆", "YJV", "3×25", "米", BigDecimal.ONE.negate(), BigDecimal.ONE, "")))
                    .isInstanceOf(BusinessException.class);

            // Zero quantity must preserve the stored average cost, including its precision.
            tx.executeWithoutResult(status -> service.manualInbound(9L, "manual-positive-entry", "电缆", "YJV", "3×25", "米",
                    new BigDecimal("3"), new BigDecimal("0.3333"), "期初库存"));
            BigDecimal existingPrice = jdbc.queryForObject("SELECT unit_price FROM inventory_balance WHERE id=?", BigDecimal.class, balanceId);
            BigDecimal existingAmount = jdbc.queryForObject("SELECT inventory_amount FROM inventory_balance WHERE id=?", BigDecimal.class, balanceId);
            tx.executeWithoutResult(status -> service.manualInbound(9L, "manual-zero-after-stock", "电缆", "YJV", "3×25", "米",
                    BigDecimal.ZERO, BigDecimal.TEN, ""));
            assertThat(jdbc.queryForObject("SELECT unit_price FROM inventory_balance WHERE id=?", BigDecimal.class, balanceId)).isEqualByComparingTo(existingPrice);
            assertThat(jdbc.queryForObject("SELECT inventory_amount FROM inventory_balance WHERE id=?", BigDecimal.class, balanceId)).isEqualByComparingTo(existingAmount);
            assertBalance(service, balanceId, productId, "3", existingPrice.toPlainString(), existingAmount.toPlainString());

            var contract = new TradeContractRespDTO();
            contract.setId(12L); contract.setCompanyId(3L); contract.setCounterpartyCompanyId(4L);
            contract.setStatus("ACTIVE");
            when(contracts.selectById(12L)).thenReturn(contract);
            when(contracts.selectByIdForUpdate(12L)).thenReturn(contract);
            var sale = document(31L, "SALES_ORDER");
            sale.setOutboundWarehouseId(9L);
            var returned = document(32L, "RETURN_ORDER");
            returned.setOutboundWarehouseId(10L); returned.setInboundWarehouseId(9L);
            for (BusinessDocumentDO document : List.of(sale, returned)) {
                when(documents.selectById(document.getId())).thenReturn(document);
                when(documents.selectByIdForUpdate(document.getId())).thenReturn(document);
                tx.executeWithoutResult(status -> service.saveDocumentItems(document));
            }

            // Selling the exact stock keeps the same supplier SKU and balance record visible at zero.
            AuthContext.set(8L, 4L);
            tx.executeWithoutResult(status -> service.receive(31L, "INBOUND", 10L));
            tx.executeWithoutResult(status -> service.receive(31L, "INBOUND", 10L));
            long buyerProductId = jdbc.queryForObject("SELECT id FROM inventory_product WHERE company_id=4", Long.class);
            long buyerBalanceId = jdbc.queryForObject("SELECT id FROM inventory_balance WHERE product_id=?", Long.class, buyerProductId);
            AuthContext.set(7L, 3L);
            assertBalance(service, balanceId, productId, "0", "0", "0");

            // Returning the exact received stock likewise keeps the buyer's zero balance visible.
            AuthContext.set(8L, 4L);
            tx.executeWithoutResult(status -> service.receive(32L, "RECEIVE_ONLY", 10L));
            tx.executeWithoutResult(status -> service.receive(32L, "RECEIVE_ONLY", 10L));
            assertBalance(service, buyerBalanceId, buyerProductId, "0", "0", "0");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_balance", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_product", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction WHERE biz_type='SALES_ORDER_OUTBOUND'", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction WHERE biz_type='RETURN_ORDER_OUTBOUND'", Long.class)).isEqualTo(1);

            // Removing a zero balance preserves the SKU and its audit history, and is tenant scoped.
            long movementCount = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction", Long.class);
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.deleteInventoryBalance(balanceId)))
                    .isInstanceOf(BusinessException.class);
            AuthContext.set(7L, 3L);
            tx.executeWithoutResult(status -> service.deleteInventoryBalance(balanceId));
            assertThat(service.inventoryOverview().get("productCount")).isEqualTo(0);
            assertThat(service.searchProducts("YJV", 20)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_product", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction", Long.class)).isEqualTo(movementCount);

            // A later inbound restores the same product; a nonzero balance cannot be removed.
            tx.executeWithoutResult(status -> service.manualInbound(9L, "manual-after-delete", "电缆", "YJV", "3×25", "米",
                    BigDecimal.ONE, BigDecimal.TEN, "再次补货"));
            long restoredBalanceId = jdbc.queryForObject("SELECT id FROM inventory_balance WHERE product_id=?", Long.class, productId);
            assertThat(restoredBalanceId).isNotEqualTo(balanceId);
            assertBalance(service, restoredBalanceId, productId, "1", "10", "10");
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.deleteInventoryBalance(restoredBalanceId)))
                    .isInstanceOf(BusinessException.class);
            jdbc.update("UPDATE inventory_balance SET quantity=0 WHERE id=?", restoredBalanceId);
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.deleteInventoryBalance(restoredBalanceId)))
                    .isInstanceOf(BusinessException.class);
        } finally {
            AuthContext.clear();
            TestIds.reset();
            admin.execute("DROP DATABASE `" + database + "`");
        }
    }

    private static BusinessDocumentDO document(long id, String type) {
        var document = new BusinessDocumentDO();
        document.setId(id); document.setContractId(12L); document.setCompanyId(3L);
        document.setRecipientCompanyId(4L); document.setSupplierCompanyId(3L); document.setBuyerCompanyId(4L);
        document.setDocumentType(type); document.setDocumentNo(type + "-" + id);
        document.setStatus("ACKNOWLEDGED");
        document.setContent("""
                {"columns":["品名","型号","规格","单位","数量","单价","金额","备注"],
                 "rows":[["电缆","YJV","3×25","米","3","0.3333","1.00","期初库存"]]}
                """);
        return document;
    }

    @SuppressWarnings("unchecked")
    private static void assertBalance(SalesOrderInventoryServiceImpl service, long balanceId, long productId,
                                      String quantity, String price, String amount) {
        Map<String, Object> overview = service.inventoryOverview();
        assertThat(overview).containsEntry("productCount", 1);
        assertThat((List<Map<String, Object>>) overview.get("balances")).singleElement().satisfies(balance -> {
            assertThat(balance).containsEntry("id", balanceId).containsEntry("productId", productId)
                    .containsEntry("productName", "电缆").containsEntry("model", "YJV")
                    .containsEntry("specification", "3×25").containsEntry("baseUnit", "米");
            assertThat((BigDecimal) balance.get("quantity")).isEqualByComparingTo(quantity);
            assertThat((BigDecimal) balance.get("unitPrice")).isEqualByComparingTo(price);
            assertThat((BigDecimal) balance.get("inventoryAmount")).isEqualByComparingTo(amount);
        });
    }
}
