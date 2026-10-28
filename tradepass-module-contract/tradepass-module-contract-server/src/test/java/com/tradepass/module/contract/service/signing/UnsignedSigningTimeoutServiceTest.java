package com.tradepass.module.contract.service.signing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.trade.api.approval.ApprovalOperations;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnsignedSigningTimeoutServiceTest {
    private static final String TASK = "unsigned-v2-task";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 2, 28, 12, 30);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-28T04:30:00Z"), ZoneOffset.UTC);

    @Test void aCalendarMonthEndsOnFebruarysLastDayRatherThanThirtyDaysAfterJanuary31() {
        var task = task(TASK);
        task.setPreparedAt(LocalDateTime.of(2026, 1, 31, 12, 30));

        assertThat(UnsignedSigningTimeoutService.due(task, NOW.minusNanos(1))).isFalse();
        assertThat(UnsignedSigningTimeoutService.due(task, NOW)).isTrue();
        assertThat(UnsignedSigningTimeoutService.due(task, NOW.plusNanos(1))).isTrue();

        task.setPreparedAt(LocalDateTime.of(2028, 1, 31, 12, 30));
        assertThat(UnsignedSigningTimeoutService.due(task, LocalDateTime.of(2028, 2, 28, 12, 30))).isFalse();
        assertThat(UnsignedSigningTimeoutService.due(task, LocalDateTime.of(2028, 2, 29, 12, 30))).isTrue();
    }

    @Test void theMonthStartsWhenTheSigningTaskWasPreparedRatherThanTheOriginalContractDate() {
        var task = task(TASK);
        task.setCreatedAt(LocalDateTime.of(2025, 12, 1, 0, 0));
        task.setPreparedAt(LocalDateTime.of(2026, 2, 1, 12, 30));
        assertThat(UnsignedSigningTimeoutService.due(task, NOW)).isFalse();
        assertThat(UnsignedSigningTimeoutService.due(task, LocalDateTime.of(2026, 3, 1, 12, 30))).isTrue();
        task.setPreparedAt(null);
        assertThat(UnsignedSigningTimeoutService.due(task, NOW)).isTrue();
        task.setCreatedAt(null);
        assertThat(UnsignedSigningTimeoutService.due(task, NOW)).isFalse();
    }

    @Test void discoveryAndTheClaimWindowUseBeijingTimeEvenWhenTheClockUsesUtc() {
        var f = new Fixture();
        f.service.runBatch();

        verify(f.tasks).findUnsignedTimeoutCandidates(NOW, NOW.minusDays(28), 20);
        verify(f.intents).due(NOW, 20);
        verify(f.intents).claim(TASK, NOW);
    }

    @Test void unclaimedWorkNeverAcquiresContractLocksOrContactsTheProcessor() {
        var f = new Fixture();
        when(f.intents.claim(TASK, NOW)).thenReturn(false);

        f.service.runBatch();

        verifyNoInteractions(f.contracts, f.processor, f.audit, f.notifications, f.transactions);
        verify(f.tasks, never()).selectOne(any(Wrapper.class));
        verify(f.intents, never()).pending(anyString());
    }

    @Test void pendingIsReadAgainAfterTheContractAndTaskLocksBeforeAnyExternalProcessing() {
        var f = new Fixture();
        f.service.runBatch();

        var order = inOrder(f.contracts, f.tasks, f.intents, f.processor);
        order.verify(f.contracts).selectOne(any(Wrapper.class));
        order.verify(f.tasks).selectOne(any(Wrapper.class));
        order.verify(f.intents).pending(TASK);
        order.verify(f.processor).process(f.task, f.intent);
    }

    @Test void aCompletedIntentDiscoveredWhileWaitingForLocksIsNeverReplayed() {
        var f = new Fixture();
        when(f.intents.pending(TASK)).thenReturn(null);

        f.service.runBatch();

        verifyNoInteractions(f.processor, f.audit, f.notifications);
        verify(f.tasks, never()).updateById(any(FadadaContractSignTaskDO.class));
        verify(f.intents, never()).complete(anyString(), anyBoolean());
    }

    @Test void aManualTakeoverUsesTheFreshIntentAndDoesNotApplyAnAutomaticContractTransition() {
        var f = new Fixture();
        var manual = new SigningCancellationIntentStore.Intent(TASK, 12, 2, false,
                "发起方撤回合同", List.of());
        when(f.intents.pending(TASK)).thenReturn(manual);
        when(f.processor.process(f.task, manual)).thenReturn(SigningCancellationProcessor.Result.CANCELLED);
        // The original manual transaction may already have committed its local lifecycle change.
        f.contract.setStatus("CANCELLED");

        f.service.runBatch();

        verify(f.processor).process(f.task, manual);
        verify(f.processor, never()).process(f.task, f.intent);
        verify(f.contracts, never()).update(isNull(), any(Wrapper.class));
        verifyNoInteractions(f.audit, f.notifications);
        verify(f.intents).complete(TASK, false);
    }

    @Test void aFailedManualWithdrawalCanFinishItsLocalPendingTransitionAfterTheRemoteTaskWasCancelled() {
        var f = new Fixture();
        var manual = new SigningCancellationIntentStore.Intent(TASK, 12, 2, false,
                "发起方撤回合同", List.of());
        when(f.intents.pending(TASK)).thenReturn(manual);
        when(f.processor.process(f.task, manual)).thenReturn(SigningCancellationProcessor.Result.CANCELLED);

        f.service.runBatch();

        verify(f.contracts).update(isNull(), any(Wrapper.class));
        verify(f.audit).logAs(3L, 0L, "CONTRACT", 12L, "CANCEL_RECOVERED", "恢复发起方撤回合同 HT-TEST");
        verify(f.intents).complete(TASK, false);
        verifyNoInteractions(f.notifications);
    }

    @Test void historicalRefundRecoveryIsQueuedWithoutChangingTheContractsNewPendingVersion() {
        var f = new Fixture();
        f.contract.setVersionNo(3);
        f.task.setProviderStatus("task_terminated");
        var recovery = new SigningCancellationIntentStore.Intent(TASK, 12, 2, false,
                SigningCancellationProcessor.REFUND_RECOVERY_REASON, List.of());
        when(f.tasks.findStoppedUnsignedRefundCandidates(20)).thenReturn(List.of(f.task));
        when(f.intents.due(NOW, 20)).thenReturn(List.of(recovery));
        when(f.intents.pending(TASK)).thenReturn(recovery);
        when(f.processor.process(f.task, recovery)).thenReturn(SigningCancellationProcessor.Result.CANCELLED);

        f.service.runBatch();

        verify(f.intents).request(f.task, SigningCancellationProcessor.REFUND_RECOVERY_REASON, false);
        verify(f.processor).process(f.task, recovery);
        verify(f.tasks).updateById(f.task);
        verify(f.contracts, never()).update(isNull(), any(Wrapper.class));
        verify(f.intents).complete(TASK, false);
        verifyNoInteractions(f.audit, f.notifications);
        assertThat(f.contract.getStatus()).isEqualTo("PENDING");
        assertThat(f.contract.getVersionNo()).isEqualTo(3);
    }

    @Test void aTaskWhichIsNotYetOneMonthOldAfterLockingRemainsPending() {
        var f = new Fixture();
        f.task.setPreparedAt(LocalDateTime.of(2026, 2, 1, 0, 0));

        f.service.runBatch();

        verifyNoInteractions(f.processor, f.audit, f.notifications);
        verify(f.tasks, never()).updateById(any(FadadaContractSignTaskDO.class));
        verify(f.intents, never()).complete(anyString(), anyBoolean());
    }

    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "VOIDED", "REJECTED", "CANCELLED"})
    void aContractThatStoppedBeingPendingOnlyRestoresOwnedBlocksAndRetiresItsTimeout(String status) {
        var f = new Fixture(); f.contract.setStatus(status);

        f.service.runBatch();

        verify(f.processor).restoreOwnedBlocks(f.task);
        verify(f.processor, never()).process(any(), any());
        verify(f.intents).complete(TASK, true);
        verify(f.contracts, never()).update(isNull(), any(Wrapper.class));
        verifyNoInteractions(f.audit, f.notifications);
    }

    @Test void aHistoricalVersionCannotCancelTheNewPendingVersion() {
        var f = new Fixture(); f.contract.setVersionNo(3);

        f.service.runBatch();

        verify(f.processor).restoreOwnedBlocks(f.task);
        verify(f.processor, never()).process(any(), any());
        verify(f.intents).complete(TASK, true);
        verify(f.contracts, never()).update(isNull(), any(Wrapper.class));
        verifyNoInteractions(f.audit, f.notifications);
    }

    @Test void anArchivedTaskCannotBeAutomaticallyCancelledEvenIfTheContractStatusIsStale() {
        var f = new Fixture(); f.task.setArchivedAt(NOW.minusDays(1));

        f.service.runBatch();

        verify(f.processor).restoreOwnedBlocks(f.task);
        verify(f.processor, never()).process(any(), any());
        verify(f.intents).complete(TASK, true);
        verifyNoInteractions(f.audit, f.notifications);
    }

    @Test void anAbolishTaskCannotBeAutomaticallyCancelledAsAnUnsignedOriginalContract() {
        var f = new Fixture(); f.task.setAbolishedSignTaskId("abolish-task");

        f.service.runBatch();

        verify(f.processor).restoreOwnedBlocks(f.task);
        verify(f.processor, never()).process(any(), any());
        verify(f.intents).complete(TASK, true);
        verifyNoInteractions(f.audit, f.notifications);
    }

    @Test void anySignatureObservedByTheProcessorPreservesThePendingContractAndClosesTheTimeoutAsSkipped() {
        var f = new Fixture();
        when(f.processor.process(f.task, f.intent)).thenReturn(SigningCancellationProcessor.Result.SIGNED);

        f.service.runBatch();

        verify(f.tasks).updateById(f.task);
        verify(f.contracts, never()).update(isNull(), any(Wrapper.class));
        verify(f.intents).complete(TASK, true);
        verifyNoInteractions(f.audit, f.notifications);
    }

    @Test void confirmedUnsignedCancellationUpdatesOnlyTheCurrentPendingVersionAndRecordsASystemResultForBothParties() {
        var f = new Fixture();
        f.service.runBatch();

        var change = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(f.contracts).update(isNull(), change.capture());
        assertThat(change.getValue().getSqlSegment()).contains("id =", "version_no =", "status =");
        assertThat(change.getValue().getParamNameValuePairs().values()).contains(12L, 2, "PENDING", "CANCELLED");
        assertThat(change.getValue().getSqlSet()).contains("status=", "approved_by=", "approved_at=");
        verify(f.audit).logAs(3L, 0L, "CONTRACT", 12L, "SIGNING_TIMEOUT",
                SigningCancellationProcessor.TIMEOUT_REASON + " HT-TEST");
        for (long recipient : List.of(3L, 4L)) {
            verify(f.notifications).recordResult(recipient, 3L, "CONTRACT", 12L, null,
                    "CANCELLED", SigningCancellationProcessor.TIMEOUT_REASON,
                    "合同 HT-TEST 创建签署任务满一个月且双方均未签署，已自动撤回。", null);
        }
        verify(f.intents).complete(TASK, false);
        verify(f.transactions).commit(any());
    }

    @Test void aMissingCounterpartyDoesNotPreventAnOtherwiseConfirmedCancellation() {
        var f = new Fixture(); f.contract.setCounterpartyCompanyId(null);
        f.service.runBatch();

        verify(f.notifications, times(1)).recordResult(eq(3L), eq(3L), eq("CONTRACT"), eq(12L),
                isNull(), eq("CANCELLED"), anyString(), anyString(), isNull());
        verifyNoMoreInteractions(f.notifications);
        verify(f.intents).complete(TASK, false);
    }

    @Test void providerOrRefundFailureRollsBackLocalChangesAndLeavesTheIntentAvailableForALaterRun() {
        var f = new Fixture();
        when(f.processor.process(f.task, f.intent)).thenThrow(new IllegalStateException("provider unavailable"))
                .thenReturn(SigningCancellationProcessor.Result.CANCELLED);

        assertThatCode(f.service::runBatch).doesNotThrowAnyException();
        verify(f.transactions).rollback(any());
        verify(f.intents, never()).complete(anyString(), anyBoolean());
        verifyNoInteractions(f.audit, f.notifications);

        f.service.runBatch();

        verify(f.processor, times(2)).process(f.task, f.intent);
        verify(f.intents).complete(TASK, false);
        verify(f.transactions).commit(any());
    }

    @Test void aFailedContractUpdateDoesNotRetireTheDurableCancellationIntentOrEmitSuccess() {
        var f = new Fixture();
        when(f.contracts.update(isNull(), any(Wrapper.class))).thenReturn(0);

        f.service.runBatch();

        verify(f.transactions).rollback(any());
        verify(f.intents, never()).complete(anyString(), anyBoolean());
        verifyNoInteractions(f.audit, f.notifications);
    }

    @Test void aMissingTaskOrFailedCleanupRemainsPendingInsteadOfBeingDiscarded() {
        var f = new Fixture();
        when(f.tasks.selectOne(any(Wrapper.class))).thenReturn(null);
        f.service.runBatch();
        verify(f.transactions).rollback(any());
        verify(f.intents, never()).complete(anyString(), anyBoolean());

        when(f.tasks.selectOne(any(Wrapper.class))).thenReturn(f.task);
        f.contract.setVersionNo(3);
        doThrow(new IllegalStateException("unblock unavailable")).when(f.processor).restoreOwnedBlocks(f.task);
        f.service.runBatch();
        verify(f.transactions, times(2)).rollback(any());
        verify(f.intents, never()).complete(anyString(), anyBoolean());
        verifyNoInteractions(f.audit, f.notifications);
    }

    @Test void oneDiscoveryFailureDoesNotStarveAnotherEligibleTask() {
        var f = new Fixture(); var other = task("other-task"); other.setId(21L);
        when(f.tasks.findUnsignedTimeoutCandidates(NOW, NOW.minusDays(28), 20)).thenReturn(List.of(f.task, other));
        doThrow(new IllegalStateException("intent storage unavailable")).when(f.intents)
                .request(f.task, SigningCancellationProcessor.TIMEOUT_REASON, true);
        when(f.intents.due(NOW, 20)).thenReturn(List.of());

        f.service.runBatch();

        verify(f.intents).request(other, SigningCancellationProcessor.TIMEOUT_REASON, true);
        verifyNoInteractions(f.processor);
    }

    @Test void twoWorkerInstancesShareTheAtomicClaimSoOnlyOneProcessesTheSameIntent() throws Exception {
        var f = new Fixture(); var claimed = new AtomicBoolean();
        when(f.intents.claim(TASK, NOW)).thenAnswer(call -> claimed.compareAndSet(false, true));
        var otherInstance = f.newService();
        var workers = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> { start.await(); f.service.runBatch(); return null; });
            var second = workers.submit(() -> { start.await(); otherInstance.runBatch(); return null; });
            start.countDown();
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }

        verify(f.intents, times(2)).claim(TASK, NOW);
        verify(f.processor, times(1)).process(f.task, f.intent);
        verify(f.intents, times(1)).complete(TASK, false);
    }

    private static FadadaContractSignTaskDO task(String id) {
        var task = new FadadaContractSignTaskDO(); task.setId(20L); task.setContractId(12L);
        task.setVersionNo(2); task.setSignTaskId(id);
        task.setPreparedAt(LocalDateTime.of(2026, 1, 31, 12, 30));
        task.setCreatedAt(LocalDateTime.of(2026, 1, 31, 12, 29));
        return task;
    }

    private static class Fixture {
        final TradeContractMapper contracts = mock(TradeContractMapper.class);
        final FadadaContractSignTaskMapper tasks = mock(FadadaContractSignTaskMapper.class);
        final SigningCancellationIntentStore intents = mock(SigningCancellationIntentStore.class);
        final SigningCancellationProcessor processor = mock(SigningCancellationProcessor.class);
        final AuditLogService audit = mock(AuditLogService.class);
        final ApprovalOperations notifications = mock(ApprovalOperations.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final TradeContractDO contract = new TradeContractDO();
        final FadadaContractSignTaskDO task = task(TASK);
        final SigningCancellationIntentStore.Intent intent = new SigningCancellationIntentStore.Intent(
                TASK, 12, 2, true, SigningCancellationProcessor.TIMEOUT_REASON, List.of());
        final UnsignedSigningTimeoutService service;
        Fixture() {
            MybatisTestSupport.initialize(TradeContractDO.class);
            MybatisTestSupport.initialize(FadadaContractSignTaskDO.class);
            contract.setId(12L); contract.setCompanyId(3L); contract.setCounterpartyCompanyId(4L);
            contract.setContractNo("HT-TEST"); contract.setStatus("PENDING"); contract.setVersionNo(2);
            when(tasks.findStoppedUnsignedRefundCandidates(20)).thenReturn(List.of());
            when(tasks.findUnsignedTimeoutCandidates(any(), any(), eq(20))).thenReturn(List.of());
            when(intents.due(NOW, 20)).thenReturn(List.of(intent));
            when(intents.claim(TASK, NOW)).thenReturn(true);
            when(intents.pending(TASK)).thenReturn(intent);
            when(contracts.selectOne(any(Wrapper.class))).thenReturn(contract);
            when(tasks.selectOne(any(Wrapper.class))).thenReturn(task);
            when(tasks.updateById(any(FadadaContractSignTaskDO.class))).thenReturn(1);
            when(contracts.update(isNull(), any(Wrapper.class))).thenReturn(1);
            when(processor.process(task, intent)).thenReturn(SigningCancellationProcessor.Result.CANCELLED);
            when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
            service = newService();
        }
        UnsignedSigningTimeoutService newService() {
            return new UnsignedSigningTimeoutService(contracts, tasks, intents, processor,
                    audit, notifications, transactions, CLOCK);
        }
    }
}
