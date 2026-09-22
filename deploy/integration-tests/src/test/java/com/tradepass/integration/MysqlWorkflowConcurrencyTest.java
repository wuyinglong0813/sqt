package com.tradepass.integration;

import com.tradepass.module.trade.service.approval.ApprovalServiceImpl;

import com.tradepass.module.trade.service.bilateral.BilateralActionServiceImpl;

import com.tradepass.module.trade.service.inventory.SalesOrderInventoryServiceImpl;

import com.tradepass.module.trade.service.memo.PersonalMemoServiceImpl;

import com.tradepass.module.trade.service.ledger.ProjectLedgerServiceImpl;

import com.tradepass.module.settlement.service.attachment.ContractAttachmentServiceImpl;

import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperationsImpl;
import com.tradepass.module.settlement.service.reconciliation.ReconciliationAccountServiceImpl;

import com.tradepass.module.settlement.service.reconciliation.ReconciliationStatementServiceImpl;

import com.tradepass.module.contract.api.abolish.ContractAbolishRecoveryOperationsImpl;
import com.tradepass.module.contract.service.abolish.ContractAbolishRecoveryServiceImpl;

import com.tradepass.module.contract.service.abolish.ContractAbolishIntentServiceImpl;

import com.tradepass.module.contract.service.archive.ContractArchiveServiceImpl;

import com.tradepass.module.contract.service.signing.FadadaContractSigningServiceImpl;

import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.framework.storage.config.StorageProperties;
import com.tradepass.module.contract.api.contract.ContractReaderImpl;
import com.tradepass.module.contract.api.contract.dto.ContractRespDTO;
import com.tradepass.module.contract.convert.contract.TradeContractConvert;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.trade.service.approval.ApprovalService;
import com.tradepass.module.trade.service.bilateral.BilateralActionService;
import com.tradepass.module.trade.service.inventory.SalesOrderInventoryService;
import com.tradepass.module.trade.service.inventory.SalesOrderSignatureService;
import com.tradepass.module.trade.service.ledger.ProjectLedgerService;
import com.tradepass.module.trade.service.memo.PersonalMemoService;

import com.tradepass.module.contract.dal.mysql.signing.ContractSigningTodoSql;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.service.abolish.ContractAbolishIntentService;
import com.tradepass.module.contract.service.abolish.ContractAbolishRecoveryService;
import com.tradepass.module.contract.service.archive.ContractArchiveService;
import com.tradepass.module.contract.service.archive.ContractPdfService;
import com.tradepass.module.contract.service.signing.FadadaContractSigningService;
import com.tradepass.module.contract.service.contract.TradeService;
import com.tradepass.module.file.api.file.ObjectStorageService;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import com.tradepass.module.identity.api.counterparty.CounterpartyReaderImpl;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations.*;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.contract.dal.mysql.signing.FadadaCallbackEventMapper;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.module.settlement.service.attachment.ContractAttachmentService;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations;
import com.tradepass.module.settlement.service.reconciliation.ReconciliationStatementService;
import com.tradepass.module.settlement.dal.mysql.attachment.ContractAttachmentMapper;
import com.tradepass.module.settlement.dal.mysql.reconciliation.ReconciliationEntryMapper;
import com.tradepass.module.settlement.dal.mysql.reconciliation.ReconciliationStatementMapper;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentMapper;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentTemplateMapper;
import com.tradepass.module.trade.service.document.BusinessDocumentService;
import com.tradepass.module.trade.service.document.BusinessDocumentServiceImpl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.mybatis.core.ApplicationIds;
import com.tradepass.module.identity.framework.config.SystemPermissionInitializer;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaCallbackEventDO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt in with -Dtradepass.test.mysql.url=jdbc:mysql://.../tradepass_fix_validation_... . */
@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlWorkflowConcurrencyTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis());
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static BusinessDocumentMapper documents;
    private static TradeContractMapper contracts;
    private static FadadaContractSignTaskMapper signingTasks;
    private static FadadaCallbackEventMapper events;
    private static SysUserMapper users;
    private static ContractAttachmentMapper attachmentsMapper;
    private static ReconciliationStatementMapper statementsMapper;
    private static ReconciliationEntryMapper entriesMapper;
    private SalesOrderInventoryService inventory;
    private BusinessDocumentService documentService;
    private long documentId;
    private long warehouseId;

    @BeforeAll static void database() throws Exception {
        String url = System.getProperty("tradepass.test.mysql.url");
        if (!url.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?")) {
            throw new IllegalArgumentException("Only a dedicated tradepass_fix_validation_* database is allowed");
        }
        var dataSource = new DriverManagerDataSource(url,
                System.getProperty("tradepass.test.mysql.username", "root"),
                System.getProperty("tradepass.test.mysql.password", ""));
        jdbc = new JdbcTemplate(dataSource);
        String migrationLocation = "filesystem:" + com.tradepass.support.RepoRoot.legacyMysqlMigrations().toAbsolutePath();
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations(migrationLocation).load();
        if (flyway.info().current() == null) {
            Flyway.configure().dataSource(dataSource).locations(migrationLocation).target("24").load().migrate();
            jdbc.update("""
                    INSERT INTO fadada_callback_event
                        (event_id, event_type, subject_type, payload_sha256, status)
                    VALUES ('legacy-before-v25', 'test', 'CALLBACK', ?, 'FAILED')
                    """, "0".repeat(64));
        }
        flyway.migrate();
        flyway.validate();
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(BusinessDocumentMapper.class);
        config.addMapper(TradeContractMapper.class);
        config.addMapper(FadadaContractSignTaskMapper.class);
        config.addMapper(FadadaCallbackEventMapper.class);
        config.addMapper(SysUserMapper.class);
        config.addMapper(ContractAttachmentMapper.class);
        config.addMapper(ReconciliationStatementMapper.class);
        config.addMapper(ReconciliationEntryMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        documents = session.getMapper(BusinessDocumentMapper.class);
        contracts = session.getMapper(TradeContractMapper.class);
        signingTasks = session.getMapper(FadadaContractSignTaskMapper.class);
        events = session.getMapper(FadadaCallbackEventMapper.class);
        users = session.getMapper(SysUserMapper.class);
        attachmentsMapper = session.getMapper(ContractAttachmentMapper.class);
        statementsMapper = session.getMapper(ReconciliationStatementMapper.class);
        entriesMapper = session.getMapper(ReconciliationEntryMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.update("INSERT IGNORE INTO sys_user(id,openid) VALUES (7,'fix-test-7'),(8,'fix-test-8')");
        jdbc.update("""
                INSERT IGNORE INTO company(id,name,credit_code,legal_person_name,created_by)
                VALUES (3,'供方测试','TEST-SUPPLIER','供方法人',7),(4,'需方测试','TEST-BUYER','需方法人',8)
                """);
    }

    @BeforeEach void fixtures() {
        var access = mock(AccessControlOperations.class);
        var audit = mock(AuditLogService.class);
        var reconciliation = new ReconciliationAccountOperationsImpl(new ReconciliationAccountServiceImpl(jdbc, entriesMapper, new CounterpartyReaderImpl(mock(CounterpartyRelationMapper.class)), access));
        inventory = new SalesOrderInventoryServiceImpl(jdbc, documents, new com.tradepass.module.contract.api.contract.ContractReaderImpl(contracts), access, audit,
                new ObjectMapper(), reconciliation, mock(SalesOrderSignatureService.class), mock(UserIdentityOperations.class));
        documentService = new BusinessDocumentServiceImpl(mock(BusinessDocumentTemplateMapper.class), documents,
                new com.tradepass.module.contract.api.contract.ContractReaderImpl(contracts), mock(CompanyReader.class), access, audit, new ObjectMapper(),
                inventory, mock(UserIdentityOperations.class));
        long contractId = IDS.incrementAndGet();
        documentId = IDS.incrementAndGet();
        warehouseId = IDS.incrementAndGet();
        jdbc.update("""
                INSERT INTO trade_contract(id,company_id,counterparty_company_id,counterparty_name,
                    name,amount,status,initiated_by,direction,contract_no)
                VALUES (?,3,4,'需方测试','并发回归',20,'ACTIVE',7,'SALE',?)
                """, contractId, "HT-FIX-" + contractId);
        jdbc.update("""
                INSERT INTO business_document(id,company_id,recipient_company_id,contract_id,document_type,
                    document_no,template_id,template_name,content,created_by,status,supplier_company_id,buyer_company_id)
                VALUES (?,3,4,?,'SALES_ORDER',?,0,'测试模板','{}',7,'ISSUED',3,4)
                """, documentId, contractId, "FIX-" + documentId);
        jdbc.update("INSERT INTO warehouse(id,company_id,name,created_by) VALUES (?,4,?,8)", warehouseId, "仓库-" + warehouseId);
        item(1, "2");
    }

    @AfterEach void clearAuth() { AuthContext.clear(); }

    @Test void confirmationWinsWithdrawalAndKeepsInventoryAndLedgerConsistent() throws Exception {
        race(4, this::receive, 3, () -> documentService.withdraw(documentId));
        assertThat(status()).isEqualTo("INBOUNDED");
        assertThat(count("inventory_inbound", "source_document_id")).isEqualTo(1);
        assertThat(count("sales_order_receipt", "document_id")).isEqualTo(1);
        assertThat(count("reconciliation_entry", "source_id")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM inventory_balance WHERE warehouse_id = ?",
                BigDecimal.class, warehouseId)).isEqualByComparingTo("2");
    }

    @Test void withdrawalWinsConfirmationWithoutInventoryReceiptOrLedger() throws Exception {
        race(3, () -> documentService.withdraw(documentId), 4, this::receive);
        assertThat(status()).isEqualTo("WITHDRAWN");
        assertThat(count("inventory_inbound", "source_document_id")).isZero();
        assertThat(count("sales_order_receipt", "document_id")).isZero();
        assertThat(count("reconciliation_entry", "source_id")).isZero();
    }

    @Test void duplicateConfirmationPostsInventoryAndLedgerOnce() {
        asCompany(4, this::receive);
        asCompany(4, this::receive);
        assertThat(count("inventory_inbound", "source_document_id")).isEqualTo(1);
        assertThat(count("reconciliation_entry", "source_id")).isEqualTo(1);
    }

    @Test void repeatedProductLinesDeductTheirCombinedQuantity() {
        long sourceWarehouse = returnFixture("3", "4");
        asCompany(3, this::receive);
        assertThat(balance(sourceWarehouse)).isEqualByComparingTo("3");
        assertThat(balance(warehouseId)).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("SELECT inventory_amount FROM inventory_balance WHERE warehouse_id = ?",
                BigDecimal.class, sourceWarehouse)).isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction WHERE warehouse_id = ? AND biz_type = 'RETURN_ORDER_OUTBOUND'",
                Integer.class, sourceWarehouse)).isEqualTo(1);
    }

    @Test void repeatedLinesExceedingTotalStockRollBackEverything() {
        long sourceWarehouse = returnFixture("6", "6");
        assertThatThrownBy(() -> asCompany(3, this::receive)).isInstanceOf(BusinessException.class).hasMessageContaining("库存不足");
        assertThat(status()).isEqualTo("ISSUED");
        assertThat(balance(sourceWarehouse)).isEqualByComparingTo("10");
        assertThat(balance(warehouseId)).isNull();
        assertThat(count("inventory_transfer", "source_document_id")).isZero();
        assertThat(count("sales_order_receipt", "document_id")).isZero();
        assertThat(count("reconciliation_entry", "source_id")).isZero();
    }

    @Test void callbackClaimRejectsConcurrentWorkersAndRecoversExpiredLease() {
        FadadaCallbackEventDO event = event();
        LocalDateTime now = LocalDateTime.now();
        assertThat(events.claim(event.getId(), "first", now, now.plusMinutes(5))).isEqualTo(1);
        assertThat(events.claim(event.getId(), "duplicate", now, now.plusMinutes(5))).isZero();
        assertThat(events.findDue(now)).doesNotContain(event.getId());
        LocalDateTime later = now.plusMinutes(6);
        assertThat(events.findDue(later)).contains(event.getId());
        assertThat(events.claim(event.getId(), "recovered", later, later.plusMinutes(5))).isEqualTo(1);
        event.setStatus("PROCESSED"); event.setProcessedAt(later);
        assertThat(events.finish(event, "first")).isZero();
        assertThat(events.finish(event, "recovered")).isEqualTo(1);
        assertThat(events.claim(event.getId(), "late", later, later.plusMinutes(5))).isZero();
        assertThat(events.selectById(event.getId()).getAttemptCount()).isEqualTo(2);
    }

    @Test void failedCallbackWaitsForBackoffThenCanRetry() {
        FadadaCallbackEventDO event = event();
        LocalDateTime now = LocalDateTime.now();
        events.claim(event.getId(), "first", now, now.plusMinutes(5));
        event.setStatus("FAILED"); event.setNextAttemptAt(now.plusMinutes(1));
        events.finish(event, "first");
        assertThat(events.claim(event.getId(), "early", now, now.plusMinutes(5))).isZero();
        assertThat(events.claim(event.getId(), "retry", now.plusMinutes(2), now.plusMinutes(7))).isEqualTo(1);
    }

    @Test void migrationPreservesLegacyEventAndRedeliveryCanRestoreItsPayload() {
        Long id = jdbc.queryForObject("SELECT id FROM fadada_callback_event WHERE event_id = 'legacy-before-v25'", Long.class);
        events.restoreLegacyPayload(id, "{\"signTaskId\":\"legacy\"}", LocalDateTime.now());
        assertThat(events.selectById(id).getRetryPayload()).contains("legacy");
        assertThat(events.restoreLegacyPayload(id, "{}", LocalDateTime.now())).isZero();
    }

    @Test void noBusinessTableRequiresDatabaseGeneratedIds() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND extra LIKE '%auto_increment%'
                """, Integer.class)).isZero();
        SysUserDO first = new SysUserDO(); first.setOpenid("id-test-" + ApplicationIds.next()); first.setStatus("ACTIVE");
        users.insert(first);
        assertThat(first.getId()).isGreaterThan(9007199254740991L);
        jdbc.update("DELETE FROM sys_user WHERE id = ?", first.getId());
        SysUserDO second = new SysUserDO(); second.setOpenid(first.getOpenid()); second.setStatus("ACTIVE");
        users.insert(second);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(users.selectById(second.getId()).getOpenid()).isEqualTo(second.getOpenid());
    }

    @Test void systemPermissionsAreRestoredWithoutCreatingBusinessData() {
        Integer usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class);
        Integer companiesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM company", Integer.class);
        jdbc.update("DELETE FROM perm_def");
        var initializer = new SystemPermissionInitializer(jdbc);
        initializer.run(null);
        initializer.run(null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perm_def", Integer.class)).isEqualTo(19);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM company", Integer.class)).isEqualTo(companiesBefore);
    }

    @Test void projectsWarehousesAndMemoUpsertsUseApplicationIds() {
        AuthContext.set(7L, 3L);
        long contractId = documents.selectById(documentId).getContractId();
        var access = mock(AccessControlOperations.class);
        var audit = mock(AuditLogService.class);
        var projects = new ProjectLedgerServiceImpl(jdbc, access, audit);
        Map<String, Object> project = projects.createProject("", "ID 回归-" + documentId, "测试");
        long projectId = (Long) project.get("id");
        assertThat(projectId).isGreaterThan(9007199254740991L);
        projects.assignContracts(projectId, List.of(contractId));
        projects.dismissContractPrompt(contractId);
        assertThat(jdbc.queryForObject("SELECT project_id FROM project_contract_assignment WHERE contract_id = ?",
                Long.class, contractId)).isEqualTo(projectId);
        assertThat((Long) inventory.createWarehouse("ID 仓库-" + documentId, "").get("id"))
                .isGreaterThan(9007199254740991L);
        var memos = new PersonalMemoServiceImpl(jdbc, new com.tradepass.module.contract.api.contract.ContractReaderImpl(contracts), documents, access, audit);
        memos.save("CONTRACT", contractId, "第一版");
        Long memoId = jdbc.queryForObject("SELECT id FROM business_memo WHERE biz_id = ?", Long.class, contractId);
        memos.save("CONTRACT", contractId, "第二版");
        assertThat(memos.get("CONTRACT", contractId)).containsEntry("content", "第二版");
        assertThat(jdbc.queryForObject("SELECT id FROM business_memo WHERE biz_id = ?", Long.class, contractId))
                .isEqualTo(memoId).isGreaterThan(9007199254740991L);
    }

    @Test void attachmentStatementAndArchiveMetadataUseExactGeneratedIds() throws Exception {
        AuthContext.set(7L, 3L);
        long contractId = documents.selectById(documentId).getContractId();
        var access = mock(AccessControlOperations.class);
        var audit = mock(AuditLogService.class);
        var storage = mock(ObjectStorageService.class);
        var properties = new com.tradepass.framework.storage.config.StorageProperties();
        Map<String, byte[]> blobs = new java.util.HashMap<>();
        when(storage.putImmutable(anyString(), any(byte[].class), anyString(), anyString())).thenAnswer(call -> {
            String key = call.getArgument(0); byte[] data = call.getArgument(1);
            blobs.put(key, data);
            return new ObjectStorageService.StoredObject("CLOUDBASE_COS", "test", key, "v1", "etag",
                    "CLOUDBASE_MANAGED", data.length, call.getArgument(3));
        });
        when(storage.get(any(ObjectStorageService.ObjectReference.class))).thenAnswer(call ->
                blobs.get(call.<ObjectStorageService.ObjectReference>getArgument(0).objectKey()));
        var relations = mock(CounterpartyRelationMapper.class);
        when(relations.countActiveBetween(3L, 4L)).thenReturn(1L);
        byte[] xlsx;
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("对账单"); workbook.write(output); xlsx = output.toByteArray();
        }
        byte[] pdf = "%PDF-1.7 test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (boolean cloud : List.of(false, true)) {
            when(storage.isEnabled()).thenReturn(cloud);
            var attachments = new ContractAttachmentServiceImpl(attachmentsMapper, new com.tradepass.module.contract.api.contract.ContractReaderImpl(contracts), access, audit, storage, properties, null, null);
            long attachmentId = (Long) attachments.upload(contractId, "OTHER", "说明.pdf", pdf, null, null).get("id");
            assertThat(attachmentId).isGreaterThan(9007199254740991L);
            assertThat(attachments.getFile(attachmentId).data()).containsExactly(pdf);
            var statements = new ReconciliationStatementServiceImpl(statementsMapper, new CounterpartyReaderImpl(relations), access, audit, storage, properties);
            long statementId = (Long) statements.upload(4L, "2026-09", "", "对账.xlsx", xlsx).get("id");
            assertThat(statementId).isGreaterThan(9007199254740991L);
            assertThat(statements.getFile(statementId).data()).containsExactly(xlsx);
        }
        var pdfService = mock(ContractPdfService.class);
        when(pdfService.fileName(any())).thenReturn("合同.pdf");
        when(pdfService.generate(any())).thenReturn(pdf);
        var archives = new ContractArchiveServiceImpl(jdbc, pdfService, storage, properties);
        for (int version = 1; version <= 2; version++) {
            var payload = new ObjectMapper().convertValue(Map.of("id", Long.toString(contractId),
                    "companyId", "3", "status", "ACTIVE", "versionNo", version),
                    com.tradepass.module.contract.api.contract.dto.ContractRespDTO.class);
            if (version == 1) archives.archiveSignedPdf(payload, pdf, "provider-test", 7L);
            else archives.archiveOnApproval(payload, 7L);
            assertThat(archives.getPdf(payload, 7L).data()).containsExactly(pdf);
        }
        assertThat(jdbc.queryForObject("SELECT MIN(id) FROM contract_archive WHERE contract_id = ?",
                Long.class, contractId)).isGreaterThan(9007199254740991L);
    }

    @Test void bilateralVoidLinksGeneratedRequestIdToInventoryReversalLedgerAndNotification() {
        asCompany(4, this::receive);
        var access = mock(AccessControlOperations.class);
        var ledger = new ReconciliationAccountOperationsImpl(new ReconciliationAccountServiceImpl(jdbc, entriesMapper, new CounterpartyReaderImpl(mock(CounterpartyRelationMapper.class)), access));
        var approvals = new ApprovalServiceImpl(jdbc, access);
        var bilateral = new BilateralActionServiceImpl(jdbc, new com.tradepass.module.contract.api.contract.ContractReaderImpl(contracts), access, mock(AuditLogService.class),
                ledger, inventory, approvals);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) asCompany(3,
                () -> bilateral.request("BUSINESS_DOCUMENT", documentId, "VOID", "ID 回归作废", false));
        long requestId = (Long) result.get("id");
        assertThat(requestId).isGreaterThan(9007199254740991L);
        asCompany(4, () -> bilateral.decide(requestId, "APPROVE", ""));
        assertThat(status()).isEqualTo("VOIDED");
        assertThat(balance(warehouseId)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT action_request_id FROM reconciliation_entry WHERE source_id = ? AND reversal_of_id IS NOT NULL",
                Long.class, requestId)).isEqualTo(requestId);
        assertThat(jdbc.queryForObject("SELECT id FROM approval_result_notification WHERE source_id = ? AND result_type = 'BILATERAL_ACTION'",
                Long.class, requestId)).isGreaterThan(9007199254740991L);
    }

    @Test void signingTodoSqlTracksTheActualSignerAndCurrentVersion() {
        long contractId = documents.selectById(documentId).getContractId();
        jdbc.update("UPDATE trade_contract SET status='PENDING',version_no=1 WHERE id=?", contractId);
        assertThat(contracts.selectContractsAwaitingSignature(3L)).extracting(com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO::getId).contains(contractId);
        assertThat(contracts.selectContractsAwaitingSignature(4L)).extracting(com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO::getId).doesNotContain(contractId);
        long taskId = IDS.incrementAndGet();
        jdbc.update("""
                INSERT INTO fadada_contract_sign_task(id,contract_id,version_no,sign_task_id,
                    provider_status,initiator_company_id,counterparty_company_id,initiator_sign_status)
                VALUES (?,?,1,?,'sign_progress',3,4,'signed')
                """, taskId, contractId, "todo-" + taskId);
        assertThat(contracts.selectContractsAwaitingSignature(3L)).extracting(com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO::getId).doesNotContain(contractId);
        assertThat(contracts.selectContractsAwaitingSignature(4L)).extracting(com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO::getId).contains(contractId);
        for (long companyId : List.of(3L, 4L)) {
            assertThat(contracts.countContractsAwaitingSignature(companyId)).isEqualTo(contracts.selectContractsAwaitingSignature(companyId).size());
            assertThat(jdbc.queryForObject(ContractSigningTodoSql.jdbcCount(), Long.class, companyId))
                    .isEqualTo(contracts.countContractsAwaitingSignature(companyId));
        }
        jdbc.update("UPDATE fadada_contract_sign_task SET counterparty_sign_status='signed' WHERE id=?", taskId);
        assertThat(contracts.selectContractsAwaitingSignature(4L)).extracting(com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO::getId).doesNotContain(contractId);
        jdbc.update("UPDATE trade_contract SET version_no=2 WHERE id=?", contractId);
        assertThat(contracts.selectContractsAwaitingSignature(3L)).extracting(com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO::getId).contains(contractId);
        assertThat(contracts.selectContractsAwaitingSignature(4L)).extracting(com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO::getId).doesNotContain(contractId);
    }

    @Test void endContractCannotStrandAnAcknowledgedDocumentBeforeInbound() {
        long contractId = documents.selectById(documentId).getContractId();
        jdbc.update("UPDATE business_document SET status='ACKNOWLEDGED' WHERE id=?", documentId);
        var service = new BilateralActionServiceImpl(jdbc, new com.tradepass.module.contract.api.contract.ContractReaderImpl(contracts), mock(AccessControlOperations.class),
                mock(AuditLogService.class), mock(ReconciliationAccountOperations.class), inventory, mock(ApprovalService.class));
        assertThatThrownBy(() -> asCompany(3, () -> service.request("CONTRACT", contractId, "END", "结束", true)))
                .hasMessageContaining("待入库");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bilateral_action_request WHERE contract_id=?",
                Integer.class, contractId)).isZero();
        jdbc.update("UPDATE business_document SET status='INBOUNDED' WHERE id=?", documentId);
        @SuppressWarnings("unchecked")
        var request = (Map<String, Object>) asCompany(3, () -> service.request("CONTRACT", contractId, "END", "结束", true));
        asCompany(4, () -> service.decide((Long) request.get("id"), "APPROVE", ""));
        assertThat(contracts.selectById(contractId).getStatus()).isEqualTo("COMPLETED");
    }

    @Test void recoveryNeedsBothCompaniesAndRetainsCancelledProviderTaskForCallbacks() {
        long contractId = documents.selectById(documentId).getContractId();
        jdbc.update("UPDATE business_document SET status='VOIDED' WHERE id=?", documentId);
        long taskId = IDS.incrementAndGet();
        String original = "original-" + taskId, abolish = "abolish-" + taskId;
        jdbc.update("""
                INSERT INTO fadada_contract_sign_task(id,contract_id,version_no,sign_task_id,abolished_sign_task_id,
                    provider_status,initiator_company_id,counterparty_company_id)
                VALUES (?,?,1,?,?,'abolishing',3,4)
                """, taskId, contractId, original, abolish);
        var access = mock(AccessControlOperations.class);
        var gateway = mock(com.tradepass.framework.fadada.core.FadadaSigningGateway.class);
        var service = new BilateralActionServiceImpl(jdbc, new com.tradepass.module.contract.api.contract.ContractReaderImpl(contracts), access, mock(AuditLogService.class),
                mock(ReconciliationAccountOperations.class), inventory, mock(ApprovalService.class));
        service.setAbolishRecoveryService(new ContractAbolishRecoveryOperationsImpl(new ContractAbolishRecoveryServiceImpl(contracts, signingTasks, gateway, access, jdbc)));
        @SuppressWarnings("unchecked")
        var voidRequest = (Map<String, Object>) asCompany(3, () -> service.request("CONTRACT", contractId, "VOID", "作废", false));
        asCompany(4, () -> service.decide((Long) voidRequest.get("id"), "APPROVE", ""));
        @SuppressWarnings("unchecked")
        var recovery = (Map<String, Object>) asCompany(3, () -> service.request("CONTRACT", contractId, "RESUME", "继续履约", false));
        long recoveryId = (Long) recovery.get("id");
        assertThatThrownBy(() -> asCompany(3, () -> service.decide(recoveryId, "APPROVE", ""))).hasMessageContaining("仅对方");
        when(gateway.status(abolish)).thenReturn(
                new com.tradepass.framework.fadada.core.FadadaSigningGateway.TaskStatus(abolish, "sign_progress", List.of()),
                new com.tradepass.framework.fadada.core.FadadaSigningGateway.TaskStatus(abolish, "task_terminated", List.of()));
        when(gateway.status(original)).thenReturn(
                new com.tradepass.framework.fadada.core.FadadaSigningGateway.TaskStatus(original, "task_finished", List.of()));
        asCompany(4, () -> service.decide(recoveryId, "APPROVE", ""));
        assertThat(contracts.selectById(contractId).getStatus()).isEqualTo("ACTIVE");
        assertThat(service.isContractReadOnly(com.tradepass.module.contract.convert.contract.TradeContractConvert.INSTANCE.toDTO(contracts.selectById(contractId)))).isFalse();
        assertThat(signingTasks.selectById(taskId).getAbolishedSignTaskId()).isNull();
        assertThat(jdbc.queryForObject("SELECT contract_id FROM fadada_cancelled_abolish_task WHERE sign_task_id=?",
                Long.class, abolish)).isEqualTo(contractId);
        assertThat(jdbc.queryForObject("SELECT status FROM bilateral_action_request WHERE id=?",
                String.class, (Long) voidRequest.get("id"))).isEqualTo("CANCELLED");
    }

    @Test void durableAbolishIntentSurvivesParentRollbackAndPreventsUnsafeRecovery() {
        long contractId = documents.selectById(documentId).getContractId();
        long taskId = IDS.incrementAndGet();
        jdbc.update("""
                INSERT INTO fadada_contract_sign_task(id,contract_id,version_no,sign_task_id,
                    provider_status,initiator_company_id,counterparty_company_id)
                VALUES (?,?,1,?,'task_finished',3,4)
                """, taskId, contractId, "intent-original-" + taskId);
        var proxy = new org.springframework.aop.framework.ProxyFactory(new ContractAbolishIntentServiceImpl(jdbc));
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                tx.getTransactionManager(), new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var intents = (ContractAbolishIntentService) proxy.getProxy();
        assertThatThrownBy(() -> asCompany(3, () -> {
            contracts.selectByIdForUpdate(contractId);
            var task = signingTasks.selectById(taskId);
            String intentId = intents.begin(task);
            jdbc.update("UPDATE fadada_contract_sign_task SET abolished_sign_task_id='remote-created' WHERE id=?", taskId);
            intents.confirm(intentId, "remote-created");
            throw new IllegalStateException("simulate crash before parent commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(signingTasks.selectById(taskId).getAbolishedSignTaskId()).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM fadada_abolish_creation_intent WHERE contract_id=?",
                String.class, contractId)).isEqualTo("UNCONFIRMED");
        var gateway = mock(com.tradepass.framework.fadada.core.FadadaSigningGateway.class);
        var recovery = new ContractAbolishRecoveryServiceImpl(contracts, signingTasks, gateway, mock(AccessControlOperations.class), jdbc);
        assertThatThrownBy(() -> asCompany(4, () -> { recovery.resumeAfterBilateralApproval(contractId); return null; }))
                .hasMessageContaining("创建结果尚未确认");
        verifyNoInteractions(gateway);
    }

    @Test void stalePermissionSnapshotCannotRestartAbolishAfterRecoveryCommits() throws Exception {
        long contractId = documents.selectById(documentId).getContractId();
        long actionId = IDS.incrementAndGet();
        jdbc.update("""
                INSERT INTO bilateral_action_request(id,contract_id,biz_type,biz_id,action_type,
                    requester_company_id,requester_user_id,approver_company_id,reason,status)
                VALUES (?,?,'CONTRACT',?,'VOID',3,7,4,'测试','APPROVED')
                """, actionId, contractId, contractId);
        var access = mock(AccessControlOperations.class);
        var gateway = mock(com.tradepass.framework.fadada.core.FadadaSigningGateway.class);
        var properties = new com.tradepass.framework.fadada.config.FadadaProperties();
        properties.setEnabled(true); properties.setAppId("test"); properties.setAppSecret("test");
        properties.setServerUrl("https://example.test"); properties.setCallbackUrl("https://example.test/callback");
        var signing = new FadadaContractSigningServiceImpl(signingTasks, contracts, mock(CompanyReader.class), access,
                mock(FadadaCompanyOperations.class), gateway, mock(ContractPdfService.class), mock(ContractArchiveService.class),
                mock(TradeService.class), properties, jdbc);
        CountDownLatch snapshot = new CountDownLatch(1), committed = new CountDownLatch(1);
        doAnswer(invocation -> {
            jdbc.queryForObject("SELECT COUNT(*) FROM bilateral_action_request WHERE contract_id=?", Long.class, contractId);
            snapshot.countDown(); await(committed); return null;
        }).when(access).requirePermission(3L, "contract_sign");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<Future<?>> oldRequest = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            asCompany(4, () -> {
                contracts.selectByIdForUpdate(contractId);
                oldRequest.set(pool.submit(() -> asCompany(3, () -> signing.abolishUrl(contractId, "作废"))));
                await(snapshot);
                jdbc.update("UPDATE bilateral_action_request SET status='CANCELLED' WHERE id=?", actionId);
                return null;
            });
            committed.countDown();
            assertThatThrownBy(() -> oldRequest.get().get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BusinessException.class)
                    .hasStackTraceContaining("请先由双方确认");
            verifyNoInteractions(gateway);
        } finally { committed.countDown(); pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS); }
    }

    private void race(long firstCompany, Supplier<?> firstAction, long secondCompany, Supplier<?> secondAction) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstReady = new CountDownLatch(1), secondStarted = new CountDownLatch(1), commit = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> asCompany(firstCompany, () -> {
                Object value = firstAction.get(); firstReady.countDown(); await(commit); return value;
            }));
            assertThat(firstReady.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> {
                secondStarted.countDown();
                return asCompany(secondCompany, secondAction);
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commit.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessException.class);
        } finally { commit.countDown(); pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS); }
    }

    private Object asCompany(long companyId, Supplier<?> action) {
        AuthContext.set(companyId == 3 ? 7L : 8L, companyId);
        try { return tx.execute(status -> action.get()); }
        finally { AuthContext.clear(); }
    }
    private Object receive() { return inventory.receive(documentId, "INBOUND", warehouseId, null, "签名.png", new byte[]{1}); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for transaction"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
    private String status() { return jdbc.queryForObject("SELECT status FROM business_document WHERE id = ?", String.class, documentId); }
    private int count(String table, String column) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, documentId); }
    private BigDecimal balance(long warehouse) { return jdbc.queryForObject("SELECT SUM(quantity) FROM inventory_balance WHERE warehouse_id = ?", BigDecimal.class, warehouse); }
    private void item(int line, String quantity) {
        jdbc.update("""
                INSERT INTO business_document_item(id,document_id,issuer_company_id,recipient_company_id,line_no,
                    product_name,specification,base_unit,quantity,unit_price,amount)
                VALUES (?,?,3,4,?,'相同商品','A','件',?,2,?)
                """, ApplicationIds.next(), documentId, line, new BigDecimal(quantity), new BigDecimal(quantity).multiply(BigDecimal.valueOf(2)));
    }
    private long returnFixture(String first, String second) {
        long source = IDS.incrementAndGet(), product = IDS.incrementAndGet();
        jdbc.update("UPDATE warehouse SET company_id = 3 WHERE id = ?", warehouseId);
        jdbc.update("INSERT INTO warehouse(id,company_id,name,created_by) VALUES (?,4,?,8)", source, "退货仓-" + source);
        jdbc.update("INSERT INTO inventory_product(id,company_id,product_name,specification,base_unit) VALUES (?,4,?,'A','件')",
                product, "相同商品-" + documentId);
        jdbc.update("INSERT INTO inventory_balance(id,company_id,warehouse_id,product_id,quantity,unit_price,inventory_amount) VALUES (?,4,?,?,10,2,20)", ApplicationIds.next(), source, product);
        jdbc.update("UPDATE business_document SET company_id=4,recipient_company_id=3,document_type='RETURN_ORDER',outbound_warehouse_id=? WHERE id=?", source, documentId);
        jdbc.update("UPDATE business_document_item SET quantity=?,amount=? WHERE document_id=?", new BigDecimal(first), new BigDecimal(first).multiply(BigDecimal.valueOf(2)), documentId);
        item(2, second);
        jdbc.update("UPDATE business_document_item SET product_name=? WHERE document_id=?", "相同商品-" + documentId, documentId);
        return source;
    }
    private FadadaCallbackEventDO event() {
        FadadaCallbackEventDO event = new FadadaCallbackEventDO();
        event.setEventId("test-" + IDS.incrementAndGet()); event.setEventType("test"); event.setSubjectType("CALLBACK");
        event.setPayloadSha256("0".repeat(64)); event.setRetryPayload("{\"signTaskId\":\"test\"}");
        event.setStatus("RECEIVED"); event.setAttemptCount(0); event.setReceivedAt(LocalDateTime.now());
        events.insert(event); return event;
    }
}
