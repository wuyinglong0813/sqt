package com.tradepass.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.service.abolish.ContractAbolishIntentService;
import com.tradepass.module.contract.service.abolish.ContractAbolishIntentServiceImpl;
import com.tradepass.module.contract.service.archive.ContractArchiveService;
import com.tradepass.module.contract.service.archive.ContractPdfService;
import com.tradepass.module.contract.service.contract.ContractReadServiceImpl;
import com.tradepass.module.contract.service.contract.TradeService;
import com.tradepass.module.contract.service.signing.FadadaContractSigningService;
import com.tradepass.module.contract.service.signing.FadadaContractSigningServiceImpl;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentMapper;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentTemplateMapper;
import com.tradepass.module.trade.service.document.BusinessDocumentService;
import com.tradepass.module.trade.service.document.BusinessDocumentServiceImpl;
import com.tradepass.module.trade.service.inventory.SalesOrderInventoryService;
import com.tradepass.module.trade.service.memo.PersonalMemoService;
import com.tradepass.module.trade.service.memo.PersonalMemoServiceImpl;
import com.tradepass.support.RepoRoot;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real InnoDB locks and the production transaction annotations, deliberately limited to three connections. */
@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlSigningReadPoolConcurrencyTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis());
    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    // Observation/setup connections are separate so observing the exhausted application pool cannot unblock it.
    private static JdbcTemplate observer;
    private static JdbcTemplate databaseAdmin;
    private static String testDatabase;
    private static DataSourceTransactionManager transactions;
    private static TradeContractMapper contracts;
    private static FadadaContractSignTaskMapper tasks;
    private static BusinessDocumentMapper documents;
    private static ContractReader reader;
    private static ContractAbolishIntentService intents;
    private long contractId;
    private long documentId;
    private FadadaContractSignTaskDO task;

    @BeforeAll static void database() throws Exception {
        String url = System.getProperty("tradepass.test.mysql.url");
        if (!url.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?")) {
            throw new IllegalArgumentException("Only a dedicated tradepass_fix_validation_* database is allowed");
        }
        String user = System.getProperty("tradepass.test.mysql.username", "root");
        String password = System.getProperty("tradepass.test.mysql.password", "");
        databaseAdmin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        // The workflow suite seeds pre-V25 rows only when its own schema is empty.
        // Never migrate or seed that shared schema: this class owns a separate, temporary database.
        testDatabase = "tradepass_fix_validation_signing_pool_" + Long.toUnsignedString(System.nanoTime());
        databaseAdmin.execute("CREATE DATABASE `" + testDatabase + "` CHARACTER SET utf8mb4");
        String dedicatedUrl = url.replaceFirst("/tradepass_fix_validation_[a-zA-Z0-9_]+", "/" + testDatabase);
        var setup = new DriverManagerDataSource(dedicatedUrl, user, password);
        observer = new JdbcTemplate(setup);
        Flyway.configure().dataSource(setup)
                .locations("filesystem:" + RepoRoot.legacyMysqlMigrations().toAbsolutePath()).load().migrate();
        var config = new HikariConfig();
        config.setJdbcUrl(dedicatedUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setPoolName("signing-three-connections");
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(2000);
        config.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=5");
        pool = new HikariDataSource(config);
        jdbc = new JdbcTemplate(pool);
        transactions = new DataSourceTransactionManager(pool);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(TradeContractMapper.class);
        configuration.addMapper(FadadaContractSignTaskMapper.class);
        configuration.addMapper(BusinessDocumentMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(pool);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        contracts = session.getMapper(TradeContractMapper.class);
        tasks = session.getMapper(FadadaContractSignTaskMapper.class);
        documents = session.getMapper(BusinessDocumentMapper.class);
        reader = proxy(new ContractReadServiceImpl(contracts, jdbc), ContractReader.class);
        intents = proxy(new ContractAbolishIntentServiceImpl(jdbc), ContractAbolishIntentService.class);
        observer.update("INSERT IGNORE INTO sys_user(id,openid) VALUES (7,'signing-pool-7'),(8,'signing-pool-8')");
        observer.update("""
                INSERT IGNORE INTO company(id,name,credit_code,legal_person_name,created_by)
                VALUES (3,'连接池供方','POOL-SUPPLIER','供方法人',7),(4,'连接池需方','POOL-BUYER','需方法人',8)
                """);
    }

    @AfterAll static void close() {
        if (pool != null) pool.close();
        if (databaseAdmin != null && testDatabase != null) databaseAdmin.execute("DROP DATABASE `" + testDatabase + "`");
    }

    @AfterEach void removeFixture() {
        observer.update("DELETE FROM fadada_abolish_creation_intent WHERE contract_id=?", contractId);
        observer.update("DELETE FROM fadada_contract_sign_task WHERE contract_id=?", contractId);
        observer.update("DELETE FROM business_document WHERE id=?", documentId);
        observer.update("DELETE FROM trade_contract WHERE id=?", contractId);
    }

    @BeforeEach void fixture() {
        contractId = IDS.incrementAndGet();
        documentId = IDS.incrementAndGet();
        long taskId = IDS.incrementAndGet();
        observer.update("""
                INSERT INTO trade_contract(id,company_id,counterparty_company_id,counterparty_name,
                    name,amount,status,initiated_by,direction,contract_no,version_no)
                VALUES (?,3,4,'连接池需方','三连接并发回归',20,'ACTIVE',7,'SALE',?,1)
                """, contractId, "HT-POOL-" + contractId);
        observer.update("""
                INSERT INTO business_document(id,company_id,recipient_company_id,contract_id,document_type,
                    document_no,template_id,template_name,content,created_by,status,supplier_company_id,buyer_company_id)
                VALUES (?,3,4,?,'SALES_ORDER',?,0,'测试模板','{}',7,'ISSUED',3,4)
                """, documentId, contractId, "POOL-" + documentId);
        observer.update("""
                INSERT INTO fadada_contract_sign_task(id,contract_id,version_no,sign_task_id,provider_status,
                    initiator_company_id,counterparty_company_id,initiator_actor_id,counterparty_actor_id)
                VALUES (?,?,1,?,'sign_progress',3,4,'supplier','buyer')
                """, taskId, contractId, "pool-task-" + taskId);
        task = tasks.selectById(taskId);
    }

    @Test void signingSyncDoesNotBorrowAFourthConnectionWhenTwoWritersWaitForItsContract() throws Exception {
        var remoteEntered = new CountDownLatch(1);
        var releaseRemote = new CountDownLatch(1);
        FadadaContractSigningService signing = signing(remoteEntered, releaseRemote);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> sync = workers.submit(() -> asSupplier(() -> signing.syncCurrent(contractId)));
            await(remoteEntered);
            Future<?> firstWriter = workers.submit(() -> reader.selectByIdForUpdate(contractId));
            Future<?> secondWriter = workers.submit(() -> reader.selectByIdForUpdate(contractId));
            awaitCondition(() -> rowLockWaiters() == 2, "two writers must really wait on the InnoDB contract lock");
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(3);
            releaseRemote.countDown();
            assertThat(sync.get(4, TimeUnit.SECONDS)).isNotNull();
            firstWriter.get(4, TimeUnit.SECONDS);
            secondWriter.get(4, TimeUnit.SECONDS);
            assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        } finally {
            releaseRemote.countDown();
            stop(workers);
        }
    }

    @Test void pdfAuthorizationAndMemoReadFinishWhileSigningStillHoldsTheContractLock() throws Exception {
        var remoteEntered = new CountDownLatch(1);
        var releaseRemote = new CountDownLatch(1);
        var access = mock(AccessControlOperations.class);
        BusinessDocumentService documentService = new BusinessDocumentServiceImpl(
                mock(BusinessDocumentTemplateMapper.class), documents, reader, mock(CompanyReader.class), access,
                mock(AuditLogService.class), new ObjectMapper(), mock(SalesOrderInventoryService.class),
                mock(UserIdentityOperations.class));
        PersonalMemoService memos = new PersonalMemoServiceImpl(jdbc, reader, documents, access, mock(AuditLogService.class));
        FadadaContractSigningService signing = signing(remoteEntered, releaseRemote);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> sync = workers.submit(() -> asSupplier(() -> signing.syncCurrent(contractId)));
            await(remoteEntered);
            Future<?> pdf = workers.submit(() -> asSupplier(() -> documentService.getDocument(documentId)));
            Future<?> memo = workers.submit(() -> asSupplier(() -> memos.get("CONTRACT", contractId)));
            assertThat(pdf.get(2, TimeUnit.SECONDS)).isNotNull();
            assertThat(memo.get(2, TimeUnit.SECONDS)).isNotNull();
            assertThat(sync.isDone()).as("remote sync is still paused while both read endpoints finish").isFalse();
            assertThat(rowLockWaiters()).isZero();
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isOne();
            releaseRemote.countDown();
            sync.get(4, TimeUnit.SECONDS);
        } finally {
            releaseRemote.countDown();
            stop(workers);
        }
    }

    @Test void committedIntentIsVisibleAfterAnEarlierReadAndWaitingForTheContractLock() throws Exception {
        var snapshotRead = new CountDownLatch(1);
        var intentCommitted = new CountDownLatch(1);
        var waiter = new AtomicReference<Future<List<ContractAbolishIntentService.PendingIntent>>>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            String intentId = readCommitted().execute(creator -> {
                contracts.selectByIdForUpdate(contractId);
                waiter.set(worker.submit(() -> readCommitted().execute(waiting -> {
                    assertThat(intentCount()).isZero();
                    snapshotRead.countDown();
                    await(intentCommitted);
                    contracts.selectByIdForUpdate(contractId);
                    return intents.pending(task);
                })));
                await(snapshotRead);
                String id = intents.begin(task);
                intentCommitted.countDown();
                awaitCondition(() -> rowLockWaiters() == 1, "the earlier reader must wait for the creator's contract lock");
                creator.setRollbackOnly();
                return id;
            });
            assertThat(waiter.get().get(4, TimeUnit.SECONDS))
                    .extracting(ContractAbolishIntentService.PendingIntent::id).containsExactly(intentId);
            assertThat(intentCount()).isOne();
        } finally {
            intentCommitted.countDown();
            stop(worker);
        }
    }

    @Test void emptyPendingReadDoesNotBlockIndependentBeginAndIntentSurvivesParentRollback() {
        String intentId = readCommitted().execute(parent -> {
            contracts.selectByIdForUpdate(contractId);
            assertThat(intents.pending(task)).isEmpty();
            String id = intents.begin(task);
            assertThat(intents.pending(task)).extracting(ContractAbolishIntentService.PendingIntent::id).containsExactly(id);
            parent.setRollbackOnly();
            return id;
        });
        assertThat(observer.queryForObject("SELECT status FROM fadada_abolish_creation_intent WHERE id=?",
                String.class, intentId)).isEqualTo("UNCONFIRMED");
    }

    private FadadaContractSigningService signing(CountDownLatch entered, CountDownLatch release) {
        var gateway = mock(FadadaSigningGateway.class);
        when(gateway.status(anyString())).thenAnswer(call -> {
            assertThat(DataSourceUtils.getConnection(pool).getTransactionIsolation())
                    .as("the real syncCurrent transaction must use READ_COMMITTED").isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            entered.countDown();
            await(release);
            return new FadadaSigningGateway.TaskStatus(task.getSignTaskId(), "sign_progress", List.of());
        });
        var properties = new FadadaProperties();
        properties.setEnabled(true);
        properties.setAppId("test");
        properties.setAppSecret("test");
        properties.setServerUrl("https://example.test");
        properties.setCallbackUrl("https://example.test/callback");
        var service = new FadadaContractSigningServiceImpl(tasks, contracts, mock(CompanyReader.class),
                mock(AccessControlOperations.class), mock(FadadaCompanyOperations.class), gateway,
                mock(ContractPdfService.class), mock(ContractArchiveService.class), mock(TradeService.class), properties, jdbc);
        service.setAbolishIntentService(intents);
        return proxy(service, FadadaContractSigningService.class);
    }

    private int rowLockWaiters() {
        return observer.queryForObject("""
                SELECT COUNT(DISTINCT waits.REQUESTING_ENGINE_TRANSACTION_ID)
                FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.data_locks requested
                  ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                WHERE requested.OBJECT_SCHEMA = DATABASE() AND requested.OBJECT_NAME = 'trade_contract'
                """, Integer.class);
    }

    private long intentCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fadada_abolish_creation_intent WHERE contract_id=?",
                Long.class, contractId);
    }

    private static TransactionTemplate readCommitted() {
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private static <T> T proxy(Object target, Class<T> type) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }

    private static <T> T asSupplier(Supplier<T> action) {
        AuthContext.set(7L, 3L);
        try { return action.get(); }
        finally { AuthContext.clear(); }
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(8, TimeUnit.SECONDS)).as("concurrent test barrier").isTrue(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as(description).isLessThan(deadline);
            try { Thread.sleep(10); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
        }
    }

    private static void stop(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
    }
}
