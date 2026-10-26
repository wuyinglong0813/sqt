package com.tradepass.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentMapper;
import com.tradepass.module.trade.service.inventory.SalesOrderInventoryServiceImpl;
import com.tradepass.module.trade.service.inventory.SalesOrderSignatureService;
import com.tradepass.module.trade.service.retail.RetailService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlInventoryModelTest {
    @Test
    void migrationPreservesLegacyProductsAndManualEntriesSeparateModelsWithoutDuplicatingRetries() {
        String base = System.getProperty("tradepass.test.mysql.url");
        if (!base.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only isolated tradepass_fix_validation_* databases are allowed");
        String user = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, user, password));
        String database = "tradepass_fix_validation_model_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        try {
            var source = new DriverManagerDataSource(base.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + database), user, password);
            var jdbc = new JdbcTemplate(source);
            String location = "filesystem:" + RepoRoot.find().resolve("tradepass-business/src/main/resources/db/business").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("9").load().migrate();
            jdbc.update("INSERT INTO inventory_product(id,company_id,product_name,specification,base_unit) VALUES(77,4,'电缆','3×25','米')");
            var flyway = Flyway.configure().dataSource(source).locations(location).load();
            flyway.migrate(); flyway.validate();
            assertThat(jdbc.queryForObject("SELECT model FROM inventory_product WHERE id=77", String.class)).isEmpty();
            jdbc.update("INSERT INTO warehouse(id,company_id,name,created_by) VALUES(9,4,'型号测试仓',8)");
            var access = mock(AccessControlOperations.class);
            var service = new SalesOrderInventoryServiceImpl(jdbc, mock(BusinessDocumentMapper.class),
                    mock(ContractReader.class), access, mock(AuditLogService.class), new ObjectMapper(), null,
                    mock(SalesOrderSignatureService.class), mock(UserIdentityOperations.class));
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            AuthContext.set(8L, 4L);
            TestIds.use(101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L, 109L, 110L, 111L, 112L);
            tx.executeWithoutResult(status -> {
                service.manualInbound(9L, "manual-model-test-a", "电缆", "YJV", "3×25", "米", new BigDecimal("2"), new BigDecimal("3.5"), "");
                service.manualInbound(9L, "manual-model-test-a", "电缆", "YJV", "3×25", "米", new BigDecimal("2"), new BigDecimal("3.5"), "");
                service.manualInbound(9L, "manual-model-test-b", "电缆", "YJLV", "3×25", "米", new BigDecimal("5"), new BigDecimal("2"), "");
                service.manualInbound(9L, "manual-model-test-c", "电缆", "3×25", "米", BigDecimal.ONE, BigDecimal.ONE, "");
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_product WHERE company_id=4", Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_manual_entry", Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_balance b JOIN inventory_product p ON p.id=b.product_id WHERE p.model='YJV'", BigDecimal.class)).isEqualByComparingTo("2");
            assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_balance b JOIN inventory_product p ON p.id=b.product_id WHERE p.model='YJLV'", BigDecimal.class)).isEqualByComparingTo("5");
            assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_balance WHERE product_id=77", BigDecimal.class)).isEqualByComparingTo("1");
            assertThat(service.searchProducts("YJLV", 20)).singleElement().satisfies(item -> {
                assertThat(item).containsEntry("model", "YJLV").containsEntry("specification", "3×25");
            });
            assertThat(service.inventoryOverview().get("productCount")).isEqualTo(3);
            // Exercise the real stock SQL: a modelled sale/return must touch only
            // its own SKU even when name/specification/unit are identical.
            jdbc.update("""
                    INSERT INTO retail_document(id,company_id,customer_id,request_id,document_no,document_type,
                        status,original_document_id,order_date,amount,customer_snapshot,company_name,prepared_by_name,created_by)
                    VALUES(201,4,200,'model-retail-sale-201','XS-MODEL-201','SALES_ORDER','CONFIRMED',NULL,CURRENT_DATE,3.5,'{}','测试企业','测试人',8),
                          (202,4,200,'model-retail-back-202','TH-MODEL-202','RETURN_ORDER','CONFIRMED',201,CURRENT_DATE,3.5,'{}','测试企业','测试人',8)
                    """);
            jdbc.update("""
                    INSERT INTO retail_document_item(id,document_id,line_no,original_item_id,product_name,model,specification,base_unit,quantity,unit_price,amount)
                    VALUES(211,201,1,NULL,'电缆','YJV','3×25','米',1,3.5,3.5),
                          (212,202,1,211,'电缆','YJV','3×25','米',1,3.5,3.5)
                    """);
            var retail = new RetailService(jdbc, access, mock(CompanyReader.class), mock(UserIdentityOperations.class),
                    mock(AuditLogService.class), new ObjectMapper());
            TestIds.use(301L, 302L, 303L, 304L, 305L, 306L);
            tx.executeWithoutResult(status -> { retail.processStock(201L, 9L); retail.processStock(201L, 9L); });
            assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_balance b JOIN inventory_product p ON p.id=b.product_id WHERE p.model='YJV'", BigDecimal.class)).isEqualByComparingTo("1");
            assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_balance b JOIN inventory_product p ON p.id=b.product_id WHERE p.model='YJLV'", BigDecimal.class)).isEqualByComparingTo("5");
            tx.executeWithoutResult(status -> { retail.processStock(202L, 9L); retail.processStock(202L, 9L); });
            assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_balance b JOIN inventory_product p ON p.id=b.product_id WHERE p.model='YJV'", BigDecimal.class)).isEqualByComparingTo("2");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction WHERE biz_id IN(201,202)", Long.class)).isEqualTo(2);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {
            AuthContext.clear(); TestIds.reset();
            admin.execute("DROP DATABASE `" + database + "`");
        }
    }
}
