package com.tradepass.integration;

import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.service.signing.SigningCancellationIntentStore;
import com.tradepass.support.RepoRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "tradepass.test.mysql.url", matches = ".+")
class MysqlSigningCancellationIntentTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T03:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-28T11:00:00");
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private SigningCancellationIntentStore store;

    @BeforeAll static void migrate() {
        String url = System.getProperty("tradepass.test.mysql.url");
        if (!url.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only an isolated tradepass_fix_validation_* database is allowed");
        var source = new DriverManagerDataSource(url, System.getProperty("tradepass.test.mysql.username", "root"),
                System.getProperty("tradepass.test.mysql.password", ""));
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).locations("filesystem:" + RepoRoot.find().resolve("sql/mysql")).load().migrate();
    }

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM fadada_signing_cancellation_intent");
        store = new SigningCancellationIntentStore(jdbc, manager, CLOCK);
    }

    @Test void intentSurvivesOuterRollbackWhileCompletionRollsBackWithTheContract() {
        var outer = new TransactionTemplate(manager);
        assertThatThrownBy(() -> outer.execute(tx -> {
            store.request(task("task-v2", 7L, 2), "超过默认签署时间已撤回", true);
            throw new IllegalStateException("local update failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.due(NOW, 20)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT requested_at FROM fadada_signing_cancellation_intent", java.sql.Timestamp.class)
                .toLocalDateTime()).isEqualTo(NOW);
        outer.executeWithoutResult(tx -> {
            store.complete("task-v2", false);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT status FROM fadada_signing_cancellation_intent", String.class)).isEqualTo("PENDING");
        store.complete("task-v2", false);
        store.complete("task-v2", false);
        assertThat(store.pending("task-v2")).isNull();
        assertThat(store.pending("missing-task")).isNull();
        assertThat(store.due(NOW.plusDays(1), 20)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT completed_at FROM fadada_signing_cancellation_intent", java.sql.Timestamp.class)
                .toLocalDateTime()).isEqualTo(NOW);
    }

    @Test void concurrentRequestsForTheSameTaskCannotCreateDuplicateIntentsOrLoseActorRecoveryData() throws Exception {
        var results = concurrent(List.of(
                () -> store.request(task("task-v2", 7L, 2), "签署超时", true),
                () -> store.request(task("task-v2", 7L, 2), "签署超时", true)));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fadada_signing_cancellation_intent", Long.class)).isEqualTo(1);
        concurrent(List.of(
                () -> { store.rememberOwnedActor("task-v2", "supplier"); return true; },
                () -> { store.rememberOwnedActor("task-v2", "buyer"); return true; }));
        assertThat(store.ownedActors("task-v2")).containsExactlyInAnyOrder("supplier", "buyer");
        store.rememberOwnedActor("task-v2", "supplier");
        store.forgetOwnedActor("task-v2", "buyer");
        store.forgetOwnedActor("task-v2", "buyer");
        assertThat(store.ownedActors("task-v2")).containsExactly("supplier");
        store.request(task("task-v2", 7L, 2), "重复扫描", true);
        assertThat(store.ownedActors("task-v2")).containsExactly("supplier");
    }

    @Test void onlyOneWorkerCanClaimTheSamePendingAttempt() throws Exception {
        store.request(task("task-v2", 7L, 2), "签署超时", true);
        var claims = concurrent(List.of(() -> store.claim("task-v2", NOW), () -> store.claim("task-v2", NOW)));
        assertThat(claims).containsExactlyInAnyOrder(true, false);
        assertThat(store.due(NOW.plusMinutes(14), 20)).isEmpty();
        assertThat(store.due(NOW.plusMinutes(15), 20)).hasSize(1);
        assertThat(store.claim("task-v2", NOW.plusMinutes(15))).isTrue();
    }

    @Test void actorRecoveryReadsFreshOwnershipDespiteAnOuterRepeatableReadSnapshot() {
        store.request(task("task-v2", 7L, 2), "签署超时", true);
        var outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(tx -> {
            // Establish the older database snapshot, then write through the durable inner transaction.
            assertThat(jdbc.queryForObject("SELECT owned_actor_ids FROM fadada_signing_cancellation_intent", String.class))
                    .isEqualTo("[]");
            assertThat(store.pending("task-v2").ownedActors()).isEmpty();
            assertThat(store.ownedActors("task-v2")).isEmpty();
            store.rememberOwnedActor("task-v2", "supplier");
            assertThat(store.ownedActors("task-v2")).containsExactly("supplier");
            assertThat(store.pending("task-v2").ownedActors()).containsExactly("supplier");
            store.forgetOwnedActor("task-v2", "supplier");
            assertThat(store.ownedActors("task-v2")).isEmpty();
            store.request(task("task-v2", 7L, 2), "人工撤回", false);
            assertThat(store.pending("task-v2").unsignedOnly()).isFalse();
            // The outer snapshot really is old; recovery reads must not accidentally share it.
            assertThat(jdbc.queryForObject("SELECT unsigned_only FROM fadada_signing_cancellation_intent", Boolean.class)).isTrue();
        });
    }

    @Test void manualWithdrawalCanTakeOverSkippedTimeoutWithoutLosingOwnedActorBlocks() {
        store.request(task("task-v2", 7L, 2), "签署超时", true);
        store.rememberOwnedActor("task-v2", "supplier");
        store.complete("task-v2", true);
        assertThat(store.due(NOW.plusDays(1), 20)).isEmpty();
        var manual = store.request(task("task-v2", 7L, 2), "发起方撤回合同", false);
        assertThat(manual.unsignedOnly()).isFalse();
        assertThat(manual.reason()).isEqualTo("发起方撤回合同");
        assertThat(manual.ownedActors()).containsExactly("supplier");
        var repeatedTimeout = store.request(task("task-v2", 7L, 2), "签署超时", true);
        assertThat(repeatedTimeout).isEqualTo(manual);
        assertThat(store.pending("task-v2")).isEqualTo(manual);
        assertThat(store.due(NOW, 20)).containsExactly(manual);
    }

    @Test void timeoutNeverRevivesDoneOrSkippedIntentsAndManualCannotReopenDone() {
        store.request(task("done", 7L, 2), "签署超时", true);
        store.complete("done", false);
        store.request(task("skipped", 8L, 1), "签署超时", true);
        store.complete("skipped", true);
        store.request(task("done", 7L, 2), "再次扫描", true);
        store.request(task("skipped", 8L, 1), "再次扫描", true);
        store.request(task("done", 7L, 2), "人工撤回", false);
        assertThat(store.due(NOW.plusDays(1), 20)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM fadada_signing_cancellation_intent WHERE sign_task_id='done'", String.class))
                .isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT status FROM fadada_signing_cancellation_intent WHERE sign_task_id='skipped'", String.class))
                .isEqualTo("SKIPPED");
    }

    @Test void aProviderTaskCannotBeReboundToADifferentContractOrVersion() {
        store.request(task("task-v2", 7L, 2), "签署超时", true);
        assertThatThrownBy(() -> store.request(task("task-v2", 8L, 2), "人工撤回", false))
                .hasMessageContaining("合同版本不一致");
        assertThatThrownBy(() -> store.request(task("task-v2", 7L, 3), "人工撤回", false))
                .hasMessageContaining("合同版本不一致");
        assertThat(store.due(NOW, 20).get(0).unsignedOnly()).isTrue();
    }

    @Test void malformedOwnedActorRecoveryDataFailsClosedInsteadOfDiscardingTheRecord() {
        store.request(task("task-v2", 7L, 2), "签署超时", true);
        for (String malformed : List.of("null", "{}", "[1]", "[\"supplier\",\"supplier\"]", "not-json")) {
            jdbc.update("UPDATE fadada_signing_cancellation_intent SET owned_actor_ids=? WHERE sign_task_id='task-v2'", malformed);
            assertThatThrownBy(() -> store.rememberOwnedActor("task-v2", "buyer"))
                    .hasMessageContaining("恢复记录无效");
            assertThat(jdbc.queryForObject("SELECT owned_actor_ids FROM fadada_signing_cancellation_intent", String.class))
                    .isEqualTo(malformed);
        }
    }

    private FadadaContractSignTaskDO task(String id, long contract, int version) {
        var task = new FadadaContractSignTaskDO();
        task.setSignTaskId(id); task.setContractId(contract); task.setVersionNo(version);
        return task;
    }

    private static <T> List<T> concurrent(List<Callable<T>> work) throws Exception {
        var executor = Executors.newFixedThreadPool(work.size());
        var start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (var action : work) futures.add(executor.submit(() -> { start.await(); return action.call(); }));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            return results;
        } finally { executor.shutdownNow(); }
    }
}
