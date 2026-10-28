package com.tradepass.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.tradepass.framework.audit.core.AuditLogMapper;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.service.membership.MembershipPolicyStore;
import com.tradepass.module.contract.service.membership.MembershipService;
import com.tradepass.module.contract.service.signing.SigningCancellationIntentStore;
import com.tradepass.module.contract.service.signing.SigningCancellationProcessor;
import com.tradepass.module.contract.service.signing.UnsignedSigningTimeoutService;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.trade.api.approval.ApprovalOperations;
import com.tradepass.module.trade.api.approval.ApprovalOperationsImpl;
import com.tradepass.module.trade.service.approval.ApprovalServiceImpl;
import com.tradepass.support.RepoRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real mapper SQL and transactions, including the independent remote-cancellation recovery boundary. */
@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlUnsignedSigningTimeoutTest {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static TradeContractMapper contracts;
    private static FadadaContractSignTaskMapper tasks;
    private static AuditLogMapper auditMapper;
    private MutableClock clock;
    private MembershipPolicyStore policies;
    private MembershipService membership;
    private SigningCancellationIntentStore intents;
    private SigningCancellationProcessor processor;
    private AuditLogService audit;
    private ApprovalServiceImpl approvals;
    private ApprovalOperations notifications;
    private FadadaSigningGateway gateway;
    private final Map<String, FadadaSigningGateway.TaskStatus> remote = new ConcurrentHashMap<>();
    private final AtomicInteger cancellations = new AtomicInteger();

    @BeforeAll static void migrateAndRegisterActualMappers() throws Exception {
        String url = System.getProperty("tradepass.test.mysql.url");
        if (!url.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only an isolated tradepass_fix_validation_* database is allowed");
        var source = new DriverManagerDataSource(url, System.getProperty("tradepass.test.mysql.username", "root"),
                System.getProperty("tradepass.test.mysql.password", ""));
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        var flyway = Flyway.configure().dataSource(source)
                .locations("filesystem:" + RepoRoot.find().resolve("sql/mysql")).load();
        flyway.migrate();
        flyway.validate();
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(TradeContractMapper.class);
        config.addMapper(FadadaContractSignTaskMapper.class);
        config.addMapper(AuditLogMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        var sessions = new SqlSessionTemplate(factory.getObject());
        contracts = sessions.getMapper(TradeContractMapper.class);
        tasks = sessions.getMapper(FadadaContractSignTaskMapper.class);
        auditMapper = sessions.getMapper(AuditLogMapper.class);
    }

    @BeforeEach void resetIsolatedDatabase() {
        for (String table : List.of("audit_log", "approval_result_notification", "fadada_signing_cancellation_intent",
                "membership_signing_usage", "membership_quota_pool", "membership_trial_budget",
                "membership_policy_history", "membership_paid_vip", "fadada_contract_sign_task", "trade_contract")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE membership_policy_state SET revision=0,content_hash='',content='' WHERE id=1");
        clock = new MutableClock(Instant.parse("2026-10-28T03:00:00Z"));
        policies = new MembershipPolicyStore(jdbc, manager);
        policies.apply("tradepass:\n  membership:\n    whitelist: [{company-id: '3', mode: QUOTA, quota-total: 10}]\n", "TEST");
        membership = new MembershipService(jdbc, policies, manager, clock);
        intents = new SigningCancellationIntentStore(jdbc, manager, clock);
        gateway = mock(FadadaSigningGateway.class);
        when(gateway.status(anyString())).thenAnswer(invocation -> remote.get(invocation.<String>getArgument(0)));
        doAnswer(invocation -> {
            block(invocation.getArgument(0), invocation.getArgument(1), "blocked");
            return null;
        }).when(gateway).blockActor(anyString(), anyString());
        doAnswer(invocation -> {
            block(invocation.getArgument(0), invocation.getArgument(1), "unblocked");
            return null;
        }).when(gateway).unblockActor(anyString(), anyString());
        doAnswer(invocation -> {
            remote.compute(invocation.getArgument(0), (id, old) ->
                    new FadadaSigningGateway.TaskStatus(id, "task_terminated", old.actors()));
            cancellations.incrementAndGet();
            return null;
        }).when(gateway).cancel(anyString(), anyString());
        processor = new SigningCancellationProcessor(gateway, intents, membership);
        audit = new AuditLogService(auditMapper);
        approvals = new ApprovalServiceImpl(jdbc, mock(AccessControlOperations.class));
        notifications = new ApprovalOperationsImpl(approvals);
        AuthContext.set(7L, 3L);
    }

    @AfterEach void clearAuth() { AuthContext.clear(); }

    @Test void anUnsignedTaskAtItsMonthDeadlineIsWithdrawnRefundedAndReportedToBothPartiesExactlyOnce() {
        seed(101L, localNow().minusMonths(1), localNow().minusMonths(1));
        var source = jdbc.queryForMap("SELECT * FROM membership_signing_usage WHERE contract_id=101");

        service().runBatch();
        service().runBatch();

        assertWithdrawnOnce(101L);
        assertThat(poolUsed()).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved_amount FROM membership_quota_pool", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT total_amount FROM membership_quota_pool", Long.class)).isEqualTo(10);
        var refunded = jdbc.queryForMap("SELECT * FROM membership_signing_usage WHERE contract_id=101");
        assertThat(refunded.get("contract_snapshot")).isEqualTo(source.get("contract_snapshot"));
        assertThat(refunded.get("consumed_at")).isEqualTo(source.get("consumed_at"));
        assertThat(refunded.get("sign_task_id")).isEqualTo("timeout-task-101");
        assertThat(jdbc.queryForObject("SELECT user_id FROM audit_log", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT recipient_company_id FROM approval_result_notification ORDER BY recipient_company_id", Long.class))
                .containsExactly(3L, 4L);
        assertThat(jdbc.queryForList("SELECT title FROM approval_result_notification", String.class))
                .containsOnly(SigningCancellationProcessor.TIMEOUT_REASON);
        verify(gateway, times(2)).blockActor(eq("timeout-task-101"), anyString());
        verify(gateway, never()).unblockActor(anyString(), anyString());
    }

    @Test void recentPreparedTasksAndAnyPartySignatureRemainPendingAndKeepTheirQuota() {
        seed(101L, localNow().minusMonths(1).plusSeconds(1), null);
        seed(102L, localNow().minusMonths(2), localNow().minusDays(1));
        seed(103L, localNow().minusMonths(2), null);
        actor("timeout-task-103", "buyer", "signed", "1792994359000");
        seed(104L, localNow().minusMonths(2), null);
        jdbc.update("UPDATE fadada_contract_sign_task SET initiator_sign_status='signed' WHERE contract_id=104");

        service().runBatch();

        assertThat(jdbc.queryForList("SELECT status FROM trade_contract", String.class)).containsOnly("PENDING");
        assertThat(jdbc.queryForList("SELECT status FROM membership_signing_usage", String.class)).containsOnly("CONSUMED");
        assertThat(poolUsed()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_result_notification", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT sign_task_id FROM fadada_signing_cancellation_intent", String.class))
                .containsExactly("timeout-task-103");
        assertThat(jdbc.queryForObject("SELECT status FROM fadada_signing_cancellation_intent", String.class)).isEqualTo("SKIPPED");
        assertThat(tasks.selectById(1103L).getCounterpartySignStatus()).isEqualTo("signed");
        verify(gateway, never()).blockActor(anyString(), anyString());
        verify(gateway, never()).cancel(anyString(), anyString());
    }

    @Test void aMonthUsesTheCalendarBoundaryIncludingFebruaryInsteadOfThirtyFixedDays() {
        clock.set(Instant.parse("2026-02-28T03:00:00Z"));
        seed(101L, LocalDateTime.parse("2026-01-31T11:00:00"), null);
        seed(102L, LocalDateTime.parse("2026-01-31T11:00:01"), null);

        service().runBatch();

        assertWithdrawnOnce(101L);
        assertThat(contracts.selectById(102L).getStatus()).isEqualTo("PENDING");
        assertThat(usage(102L)).isEqualTo("CONSUMED");
        assertThat(poolUsed()).isEqualTo(1);
        verify(gateway, never()).status("timeout-task-102");
    }

    @Test void twoInstancesScanningConcurrentlyCannotCancelRefundOrNotifyTwice() throws Exception {
        seed(101L, localNow().minusMonths(2), null);
        var secondMembership = new MembershipService(jdbc, new MembershipPolicyStore(jdbc, manager), manager, clock);
        var secondIntents = new SigningCancellationIntentStore(jdbc, manager, clock);
        var secondProcessor = new SigningCancellationProcessor(gateway, secondIntents, secondMembership);
        var second = new UnsignedSigningTimeoutService(contracts, tasks, secondIntents, secondProcessor,
                new AuditLogService(auditMapper), new ApprovalOperationsImpl(new ApprovalServiceImpl(jdbc,
                mock(AccessControlOperations.class))), manager, clock);
        var first = service();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var one = executor.submit(() -> { await(start); first.runBatch(); });
            var two = executor.submit(() -> { await(start); second.runBatch(); });
            start.countDown();
            one.get(20, TimeUnit.SECONDS);
            two.get(20, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }

        assertWithdrawnOnce(101L);
        assertThat(poolUsed()).isZero();
    }

    @Test void remoteTerminationAndIndependentRefundSurviveLocalRollbackAndTheNextAttemptFinishesOnce() {
        seed(101L, localNow().minusMonths(2), null);
        var failFirst = new AtomicBoolean(true);
        var flakyAudit = new AuditLogService(auditMapper) {
            @Override public void logAs(long companyId, long userId, String type, Object id, String action, String detail) {
                super.logAs(companyId, userId, type, id, action, detail);
                if (failFirst.getAndSet(false)) throw new IllegalStateException("simulate local transaction rollback");
            }
        };
        var retrying = new UnsignedSigningTimeoutService(contracts, tasks, intents, processor, flakyAudit,
                notifications, manager, clock);

        retrying.runBatch();

        assertThat(remote.get("timeout-task-101").status()).isEqualTo("task_terminated");
        assertThat(contracts.selectById(101L).getStatus()).isEqualTo("PENDING");
        assertThat(tasks.selectById(1101L).getProviderStatus()).isEqualTo("sign_progress");
        assertThat(usage(101L)).isEqualTo("REFUNDED");
        assertThat(poolUsed()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM fadada_signing_cancellation_intent", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_result_notification", Long.class)).isZero();

        clock.advanceMinutes(16);
        retrying.runBatch();
        retrying.runBatch();

        assertWithdrawnOnce(101L);
        assertThat(poolUsed()).isZero();
        verify(gateway, times(2)).blockActor(eq("timeout-task-101"), anyString());
    }

    @Test void bothPartiesResultNotificationsRollBackTogetherAndRecoverWithoutDuplicateResults() {
        seed(101L, localNow().minusMonths(2), null);
        var failFirst = new AtomicBoolean(true);
        var flakyApprovals = new ApprovalServiceImpl(jdbc, mock(AccessControlOperations.class)) {
            @Override public void recordResult(long recipient, long sourceCompany, String type, long source,
                    Long contract, String status, String title, String detail, String rejectedReason) {
                super.recordResult(recipient, sourceCompany, type, source, contract, status, title, detail, rejectedReason);
                if (recipient == 4L && failFirst.getAndSet(false))
                    throw new IllegalStateException("simulate notification rollback");
            }
        };
        var retrying = new UnsignedSigningTimeoutService(contracts, tasks, intents, processor, audit,
                new ApprovalOperationsImpl(flakyApprovals), manager, clock);

        retrying.runBatch();

        assertThat(contracts.selectById(101L).getStatus()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_result_notification", Long.class)).isZero();
        assertThat(usage(101L)).isEqualTo("REFUNDED");
        assertThat(cancellations.get()).isOne();

        clock.advanceMinutes(16);
        retrying.runBatch();

        assertWithdrawnOnce(101L);
        assertThat(poolUsed()).isZero();
    }

    @Test void aLostBlockResponseRestoresFreshOwnershipEvenAfterTheOuterTransactionReadAnEmptyIntent() {
        seed(101L, localNow().minusMonths(2), null);
        var failFirst = new AtomicBoolean(true);
        doAnswer(invocation -> {
            block("timeout-task-101", "buyer", "blocked");
            if (failFirst.getAndSet(false)) throw new BusinessException("simulate lost block response");
            return null;
        }).when(gateway).blockActor("timeout-task-101", "buyer");

        service().runBatch();

        assertThat(contracts.selectById(101L).getStatus()).isEqualTo("PENDING");
        assertThat(usage(101L)).isEqualTo("CONSUMED");
        assertThat(poolUsed()).isOne();
        assertThat(remote.get("timeout-task-101").actors())
                .extracting(FadadaSigningGateway.ActorStatus::blockStatus).containsOnly("unblocked");
        assertThat(intents.ownedActors("timeout-task-101")).isEmpty();
        assertThat(intents.pending("timeout-task-101")).isNotNull();
        assertThat(cancellations.get()).isZero();
        verify(gateway).unblockActor("timeout-task-101", "supplier");
        verify(gateway).unblockActor("timeout-task-101", "buyer");

        clock.advanceMinutes(16);
        service().runBatch();

        assertWithdrawnOnce(101L);
        assertThat(poolUsed()).isZero();
    }

    private UnsignedSigningTimeoutService service() {
        return new UnsignedSigningTimeoutService(contracts, tasks, intents, processor, audit, notifications, manager, clock);
    }

    private void seed(long id, LocalDateTime created, LocalDateTime prepared) {
        String taskId = "timeout-task-" + id;
        jdbc.update("""
                INSERT INTO trade_contract(id,contract_no,company_id,counterparty_company_id,counterparty_name,
                    direction,name,amount,version_no,status,initiated_by,created_at)
                VALUES (?, ?,3,4,'需方','SALE','超时回归',97419,2,'PENDING',7,?)
                """, id, "HT-TIMEOUT-" + id, created.minusDays(2));
        jdbc.update("""
                INSERT INTO fadada_contract_sign_task(id,contract_id,version_no,sign_task_id,provider_status,
                    initiator_company_id,counterparty_company_id,initiator_actor_id,counterparty_actor_id,
                    initiator_sign_status,counterparty_sign_status,source_file_id,doc_id,
                    prepared_at,created_at,updated_at)
                VALUES (?,?,2,?,'sign_progress',3,4,'supplier','buyer','wait_sign','wait_sign',?,'contract-doc',?,?,?)
                """, id + 1000, id, taskId, "file-" + id, prepared, created, created);
        var company = new CompanyRespDTO();
        company.setId(3L); company.setName("供方"); company.setCreditCode("TIMEOUT-CREDIT-3");
        company.setCertificationStatus("VERIFIED");
        var reservation = membership.reserve(company, id, 2, "{\"version\":2}", "a".repeat(64), "owner-corp");
        membership.confirm(reservation.id(), new FadadaSigningGateway.CreatedTask(taskId, "file-" + id, "contract-doc"));
        remote.put(taskId, new FadadaSigningGateway.TaskStatus(taskId, "sign_progress", List.of(
                new FadadaSigningGateway.ActorStatus("supplier", "wait_sign", null, "unblocked"),
                new FadadaSigningGateway.ActorStatus("buyer", "wait_sign", null, "unblocked"))));
    }

    private void assertWithdrawnOnce(long id) {
        assertThat(contracts.selectById(id).getStatus()).isEqualTo("CANCELLED");
        assertThat(tasks.selectById(id + 1000).getProviderStatus()).isEqualTo("task_terminated");
        assertThat(tasks.selectById(id + 1000).getLastError()).isEqualTo(SigningCancellationProcessor.TIMEOUT_REASON);
        assertThat(usage(id)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='SIGNING_TIMEOUT'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_result_notification WHERE result_status='CANCELLED'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM fadada_signing_cancellation_intent WHERE sign_task_id=?",
                String.class, "timeout-task-" + id)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT owned_actor_ids FROM fadada_signing_cancellation_intent WHERE sign_task_id=?",
                String.class, "timeout-task-" + id)).isEqualTo("[]");
        assertThat(cancellations.get()).isOne();
    }

    private String usage(long id) {
        return jdbc.queryForObject("SELECT status FROM membership_signing_usage WHERE contract_id=? AND version_no=2", String.class, id);
    }

    private long poolUsed() {
        return jdbc.queryForObject("SELECT consumed_amount FROM membership_quota_pool", Long.class);
    }

    private LocalDateTime localNow() { return LocalDateTime.ofInstant(clock.instant(), BEIJING); }

    private void block(String taskId, String actor, String value) {
        remote.compute(taskId, (id, old) -> new FadadaSigningGateway.TaskStatus(id, old.status(), old.actors().stream()
                .map(a -> actor.equals(a.actorId())
                        ? new FadadaSigningGateway.ActorStatus(a.actorId(), a.signStatus(), a.signTime(), value) : a).toList()));
    }

    private void actor(String taskId, String actor, String signStatus, String signTime) {
        remote.compute(taskId, (id, old) -> new FadadaSigningGateway.TaskStatus(id, old.status(), old.actors().stream()
                .map(a -> actor.equals(a.actorId())
                        ? new FadadaSigningGateway.ActorStatus(a.actorId(), signStatus, signTime, a.blockStatus()) : a).toList()));
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time;
        private MutableClock(Instant value) { time = new AtomicReference<>(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return time.get(); }
        void set(Instant value) { time.set(value); }
        void advanceMinutes(long minutes) { time.updateAndGet(value -> value.plusSeconds(minutes * 60)); }
    }
}
