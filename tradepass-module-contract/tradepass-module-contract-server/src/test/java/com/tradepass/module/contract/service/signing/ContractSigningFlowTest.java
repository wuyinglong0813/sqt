package com.tradepass.module.contract.service.signing;

import com.tradepass.module.contract.service.abolish.ContractAbolishIntentService;
import com.tradepass.module.contract.service.archive.ContractArchiveService;
import com.tradepass.module.contract.service.archive.ContractPdfService;
import com.tradepass.module.contract.service.contract.TradeService;

import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations.*;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContractSigningFlowTest {
    private final TradeContractMapper contracts = mock(TradeContractMapper.class);
    private final FadadaContractSignTaskMapper tasks = mock(FadadaContractSignTaskMapper.class);
    private final FadadaSigningGateway gateway = mock(FadadaSigningGateway.class);
    private final TradeService trade = mock(TradeService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ContractAbolishIntentService intents = mock(ContractAbolishIntentService.class);
    private FadadaContractSigningService service;
    private final TradeContractDO contract = new TradeContractDO();
    private final FadadaContractSignTaskDO task = new FadadaContractSignTaskDO();

    @BeforeEach void setUp() {
        MybatisTestSupport.initialize(FadadaContractSignTaskDO.class);
        FadadaProperties properties = new FadadaProperties();
        properties.setEnabled(true); properties.setAppId("app"); properties.setAppSecret("secret");
        properties.setServerUrl("https://api.test"); properties.setCallbackUrl("https://example.test/callback");
        service = new FadadaContractSigningServiceImpl(tasks, contracts, mock(CompanyReader.class),
                mock(AccessControlOperations.class), mock(FadadaCompanyOperations.class), gateway,
                mock(ContractPdfService.class), mock(ContractArchiveService.class), trade, properties, jdbc);
        service.setAbolishIntentService(intents);
        when(intents.begin(any())).thenReturn("intent");
        when(jdbc.queryForList(contains("action_type = 'VOID'"), eq(Long.class), eq(12L))).thenReturn(List.of(1L));
        contract.setId(12L); contract.setCompanyId(3L); contract.setCounterpartyCompanyId(4L);
        contract.setVersionNo(1); contract.setStatus("PENDING"); contract.setInitiatedBy(7L);
        task.setId(20L); task.setContractId(12L); task.setVersionNo(1); task.setSignTaskId("original");
        task.setInitiatorCompanyId(3L); task.setCounterpartyCompanyId(4L);
        task.setInitiatorActorId("supplier"); task.setCounterpartyActorId("buyer"); task.setProviderStatus("sign_progress");
        when(contracts.selectById(12L)).thenReturn(contract);
        when(contracts.selectByIdForUpdate(12L)).thenReturn(contract);
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(task);
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "sign_progress", List.of()));
        AuthContext.set(7L, 3L);
    }

    @AfterEach void clear() { AuthContext.clear(); }

    @Test void activeContractsNeverOfferAnElectronicAbolishSignature() {
        contract.setStatus("ACTIVE"); task.setProviderStatus("task_finished");
        when(jdbc.queryForObject(contains("action_type = 'VOID'"), eq(Long.class), eq(12L))).thenReturn(1L);
        var result = service.current(12L);
        assertThat(result.statusText()).isEqualTo("履约中");
        assertThat(result.canSign()).isFalse();
        assertThat(result.canAbolish()).isTrue();
        assertThat(result.abolishApproved()).isFalse();
        verifyNoInteractions(trade, gateway, jdbc);
    }

    @Test void revokedOriginalSynchronizesTheLocalContractEvenWithoutAChildRecord() {
        contract.setStatus("ACTIVE"); task.setProviderStatus("task_finished");
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "revoked", List.of()));
        var result = service.syncCurrent(12L);
        verify(trade).voidAfterElectronicAbolish(12L, 1, 7L);
        assertThat(contract.getStatus()).isEqualTo("VOIDED");
        assertThat(result.statusText()).isEqualTo("已作废");
        assertThat(result.canSign()).isFalse();
        assertThat(task.getFinishedAt()).isNotNull();
    }

    @Test void parentCallbackRecoversAndCompletesItsPreviouslyUnrecordedChild() {
        contract.setStatus("ACTIVE"); task.setArchivedAt(java.time.LocalDateTime.now());
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "task_finished", List.of(), null, "child"));
        when(gateway.status("child")).thenReturn(new FadadaSigningGateway.TaskStatus("child", "task_finished", List.of(), "original", null));
        service.syncBySignTaskId("original");
        assertThat(task.getAbolishedSignTaskId()).isEqualTo("child");
        assertThat(task.getProviderStatus()).isEqualTo("revoked");
        verify(trade).voidAfterElectronicAbolish(12L, 1, 7L);
        verify(gateway, never()).abolish(anyString(), anyString(), anyString(), anyString());
    }

    @Test void childCompletionCallbackVoidsTheCurrentContract() {
        contract.setStatus("ACTIVE"); task.setAbolishedSignTaskId("child");
        when(gateway.status("child")).thenReturn(new FadadaSigningGateway.TaskStatus("child", "task_finished", List.of(), "original", null));
        service.syncBySignTaskId("child");
        verify(trade).voidAfterElectronicAbolish(12L, 1, 7L);
        assertThat(task.getProviderStatus()).isEqualTo("revoked");
    }

    @ParameterizedTest @ValueSource(strings = {"task_terminated", "expired", "task_expired"})
    void resumedContractDoesNotReattachItsRetiredStoppedChild(String status) {
        resumedContractWithRetiredChild(status);
        var result = service.syncCurrent(12L);
        assertThat(contract.getStatus()).isEqualTo("ACTIVE");
        assertThat(task.getAbolishedSignTaskId()).isNull();
        assertThat(task.getProviderStatus()).isEqualTo("task_finished");
        assertThat(task.getInitiatorSignStatus()).isEqualTo("signed");
        assertThat(task.getCounterpartySignStatus()).isEqualTo("signed");
        assertThat(result.abolishApproved()).isFalse();
        assertThat(result.statusText()).isEqualTo("履约中");
        verifyNoInteractions(trade);
        verify(gateway, never()).abolish(anyString(), anyString(), anyString(), anyString());
    }

    @ParameterizedTest @ValueSource(strings = {"task_finished", "revoked"})
    void retiredChildRealCompletionStillVoidsTheResumedContract(String status) {
        resumedContractWithRetiredChild(status);
        var result = service.syncCurrent(12L);
        verify(trade).voidAfterElectronicAbolish(12L, 1, 7L);
        assertThat(contract.getStatus()).isEqualTo("VOIDED");
        assertThat(task.getAbolishedSignTaskId()).isNull();
        assertThat(result.status()).isEqualTo("revoked");
        assertThat(result.statusText()).isEqualTo("已作废");
    }

    @ParameterizedTest @ValueSource(strings = {"sign_progress", "sign_completed"})
    void retiredChildUnexpectedRemoteProgressRequiresReviewInsteadOfReattaching(String status) {
        resumedContractWithRetiredChild(status);
        assertThatThrownBy(() -> service.syncCurrent(12L)).hasMessageContaining("需要核实处理");
        assertThat(task.getAbolishedSignTaskId()).isNull();
        assertThat(contract.getStatus()).isEqualTo("ACTIVE");
        verifyNoInteractions(trade);
        verify(tasks, never()).updateById(any(FadadaContractSignTaskDO.class));
    }

    @Test void oldAbolishUrlCannotCreateOrChargeForAProviderAgreement() {
        contract.setStatus("ACTIVE"); task.setProviderStatus("task_finished");
        assertThatThrownBy(() -> service.abolishUrl(12L, "双方同意作废")).hasMessageContaining("商签通内由双方确认");
        assertThat(contract.getStatus()).isEqualTo("ACTIVE");
        assertThat(task.getProviderStatus()).isEqualTo("task_finished");
        verifyNoInteractions(gateway, intents, trade);
        verify(tasks, never()).updateById(any(FadadaContractSignTaskDO.class));
    }

    private void resumedContractWithRetiredChild(String childStatus) {
        contract.setStatus("ACTIVE"); task.setArchivedAt(java.time.LocalDateTime.now());
        task.setProviderStatus("task_finished");
        task.setInitiatorSignStatus("signed"); task.setCounterpartySignStatus("signed");
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "task_finished", List.of(), null, "retired"));
        when(gateway.status("retired")).thenReturn(new FadadaSigningGateway.TaskStatus("retired", childStatus, List.of(), "original", null));
        when(jdbc.queryForList(contains("SELECT sign_task_id FROM fadada_cancelled_abolish_task"),
                eq(String.class), eq("retired"), eq(12L), eq(1), eq("original"))).thenReturn(List.of("retired"));
    }

    @Test void signedButUnfinishedAbolishDoesNotVoidPrematurely() {
        contract.setStatus("ACTIVE"); task.setAbolishedSignTaskId("child");
        when(gateway.status("child")).thenReturn(new FadadaSigningGateway.TaskStatus("child", "sign_completed", List.of(
                new FadadaSigningGateway.ActorStatus("supplier", "signed"),
                new FadadaSigningGateway.ActorStatus("buyer", "signed")), "original", null));
        service.syncCurrent(12L);
        assertThat(task.getProviderStatus()).isEqualTo("ABOLISH_sign_completed");
        assertThat(contract.getStatus()).isEqualTo("ACTIVE");
        verifyNoInteractions(trade);
    }

    @Test void unrelatedCompletedChildCannotVoidTheContract() {
        contract.setStatus("ACTIVE"); task.setAbolishedSignTaskId("child");
        when(gateway.status("child")).thenReturn(new FadadaSigningGateway.TaskStatus("child", "task_finished", List.of(), "unrelated", null));
        assertThatThrownBy(() -> service.syncCurrent(12L)).hasMessageContaining("对应关系");
        verifyNoInteractions(trade);
    }

    @Test void anUnpreparedContractIsActionableOnlyByItsInitiator() {
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThat(service.current(12L).canSign()).isTrue();
        assertThat(service.current(12L).statusText()).isEqualTo("待我方签署");
        AuthContext.set(8L, 4L);
        assertThat(service.current(12L).canSign()).isFalse();
        assertThat(service.current(12L).statusText()).isEqualTo("待对方签署");
    }

    @Test void recipientBecomesActionableOnlyAfterInitiatorSigns() {
        AuthContext.set(8L, 4L);
        assertThat(service.current(12L).canSign()).isFalse();
        task.setInitiatorSignStatus("signed");
        assertThat(service.current(12L).canSign()).isTrue();
        AuthContext.set(7L, 3L);
        assertThat(service.current(12L).canSign()).isFalse();
    }

    @Test void successfulPartySignatureIsShownWithoutActivatingTheTwoPartyContract() {
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "sign_progress", List.of(
                new FadadaSigningGateway.ActorStatus("supplier", "signed"),
                new FadadaSigningGateway.ActorStatus("buyer", "wait_sign"))));
        var initiator = service.syncCurrent(12L);
        assertThat(initiator.statusText()).isEqualTo("待对方签署");
        assertThat(initiator.canSign()).isFalse();
        assertThat(initiator.signedFileArchived()).isFalse();
        AuthContext.set(8L, 4L);
        var recipient = service.current(12L);
        assertThat(recipient.statusText()).isEqualTo("待我方签署");
        assertThat(recipient.canSign()).isTrue();
        assertThat(contract.getStatus()).isEqualTo("PENDING");
        verifyNoInteractions(trade);
        verify(gateway, never()).downloadSignedPdf(any(), any(), any());
    }

    @Test void bothPartySignaturesWaitForTheProviderToFinishBeforeActivation() {
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "sign_progress", List.of(
                new FadadaSigningGateway.ActorStatus("supplier", "signed"),
                new FadadaSigningGateway.ActorStatus("buyer", "signed"))));
        var result = service.syncCurrent(12L);
        assertThat(result.statusText()).isEqualTo("双方已签署，正在归档");
        assertThat(result.canSign()).isFalse();
        assertThat(result.signedFileArchived()).isFalse();
        assertThat(contract.getStatus()).isEqualTo("PENDING");
        verifyNoInteractions(trade);
        verify(gateway, never()).downloadSignedPdf(any(), any(), any());
    }

    @Test void unsignedProviderActorsNeverClaimASuccessfulSignature() {
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "sign_progress", List.of(
                new FadadaSigningGateway.ActorStatus("supplier", "wait_sign"),
                new FadadaSigningGateway.ActorStatus("buyer", "wait_sign"))));
        var result = service.syncCurrent(12L);
        assertThat(result.statusText()).isEqualTo("待我方签署");
        assertThat(result.canSign()).isTrue();
        assertThat(result.signedFileArchived()).isFalse();
        AuthContext.set(8L, 4L);
        assertThat(service.current(12L).statusText()).isEqualTo("待对方签署");
        assertThat(service.current(12L).canSign()).isFalse();
        verifyNoInteractions(trade);
    }

    @Test void recipientCannotOpenProviderSigningBeforeItsTurn() {
        AuthContext.set(8L, 4L);
        assertThatThrownBy(() -> service.signUrl(12L)).hasMessageContaining("发起方先完成");
        verify(gateway, never()).actorUrl(anyString(), anyString(), anyString(), anyString());
    }

    @Test void recipientOpeningAnUnpreparedContractDoesNotCreateAnOrphanProviderTask() {
        AuthContext.set(8L, 4L);
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.signUrl(12L)).hasMessageContaining("发起方先完成");
        verifyNoInteractions(gateway);
    }

    @Test void terminatedSigningIsNotOfferedAsAnActionableTask() {
        task.setProviderStatus("task_terminated");
        assertThat(service.current(12L).canSign()).isFalse();
    }

    @Test void orphanedProviderChildIsRecoveredOnlyFromBidirectionalProviderEvidence() {
        contract.setStatus("ACTIVE"); task.setArchivedAt(java.time.LocalDateTime.now());
        task.setInitiatorSignStatus("signed"); task.setCounterpartySignStatus("signed");
        when(intents.pending(task)).thenReturn(List.of(new ContractAbolishIntentService.PendingIntent("intent", null)));
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "task_finished", List.of(), null, "child"));
        when(gateway.status("child")).thenReturn(new FadadaSigningGateway.TaskStatus("child", "sign_progress", List.of(), "original", null));
        service.syncCurrent(12L);
        assertThat(task.getAbolishedSignTaskId()).isEqualTo("child");
        assertThat(task.getInitiatorSignStatus()).isNull();
        assertThat(task.getCounterpartySignStatus()).isNull();
        verify(intents).confirm("intent", "child");
        verify(gateway, never()).abolish(anyString(), anyString(), anyString(), anyString());
    }

    @Test void unknownChildCallbackRecoversTheIntentOnceWithinItsTransaction() {
        contract.setStatus("ACTIVE");
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(null, task, task);
        when(intents.pending(task)).thenReturn(List.of(new ContractAbolishIntentService.PendingIntent("intent", null)));
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "task_finished", List.of(), null, "child"));
        when(gateway.status("child")).thenReturn(new FadadaSigningGateway.TaskStatus("child", "sign_progress", List.of(), "original", null));
        service.syncBySignTaskId("child");
        assertThat(task.getAbolishedSignTaskId()).isEqualTo("child");
        verify(intents, times(1)).confirm("intent", "child");
    }

    @Test void oldUncertainCreationDoesNotReopenTheRetiredAbolishEndpoint() {
        contract.setStatus("ACTIVE"); task.setProviderStatus("ABOLISH_CREATION_UNCERTAIN");
        assertThatThrownBy(() -> service.abolishUrl(12L, "测试")).hasMessageContaining("商签通内由双方确认");
        verifyNoInteractions(gateway, intents);
    }

    @Test void mismatchedRemoteParentCannotConfirmAnOrphanedChild() {
        contract.setStatus("ACTIVE");
        when(intents.pending(task)).thenReturn(List.of(new ContractAbolishIntentService.PendingIntent("intent", null)));
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "task_finished", List.of(), null, "child"));
        when(gateway.status("child")).thenReturn(new FadadaSigningGateway.TaskStatus("child", "sign_progress", List.of(), "unrelated", null));
        assertThatThrownBy(() -> service.syncCurrent(12L)).hasMessageContaining("对应关系");
        verify(intents, never()).confirm(anyString(), anyString());
    }

    @Test @SuppressWarnings("unchecked")
    void cancelledTaskCallbackStillChecksRealProviderCompletion() {
        contract.setStatus("ACTIVE");
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(null);
        when(jdbc.query(contains("fadada_cancelled_abolish_task"), any(RowMapper.class), eq("retired")))
                .thenReturn(List.of(new FadadaContractSigningServiceImpl.RetiredAbolishTask(12L, 1, "original")));
        when(gateway.status("retired")).thenReturn(new FadadaSigningGateway.TaskStatus("retired", "task_finished", List.of(), "original", null));
        service.syncBySignTaskId("retired");
        verify(trade).voidAfterElectronicAbolish(12L, 1, 7L);
    }

    @Test @SuppressWarnings("unchecked")
    void callbackThatWaitedForRecoveryLockRechecksItsRetiredTaskInsteadOfOriginal() {
        contract.setStatus("ACTIVE");
        FadadaContractSignTaskDO stale = new FadadaContractSignTaskDO();
        stale.setId(20L); stale.setContractId(12L); stale.setVersionNo(1);
        stale.setSignTaskId("original"); stale.setAbolishedSignTaskId("retired");
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(stale, task);
        when(jdbc.query(contains("fadada_cancelled_abolish_task"), any(RowMapper.class), eq("retired")))
                .thenReturn(List.of(new FadadaContractSigningServiceImpl.RetiredAbolishTask(12L, 1, "original")));
        when(gateway.status("retired")).thenReturn(new FadadaSigningGateway.TaskStatus("retired", "task_finished", List.of(), "original", null));
        service.syncBySignTaskId("retired");
        verify(gateway).status("retired");
        verify(gateway, never()).status("original");
        verify(trade).voidAfterElectronicAbolish(12L, 1, 7L);
    }

    @Test @SuppressWarnings("unchecked")
    void retiredChildMustMatchBothItsOwnIdAndItsRecordedParentBeforeVoiding() {
        contract.setStatus("ACTIVE"); when(tasks.selectOne(any(Wrapper.class))).thenReturn(null);
        when(jdbc.query(contains("fadada_cancelled_abolish_task"), any(RowMapper.class), eq("retired")))
                .thenReturn(List.of(new FadadaContractSigningServiceImpl.RetiredAbolishTask(12L, 1, "original")));
        when(gateway.status("retired")).thenReturn(new FadadaSigningGateway.TaskStatus("other-child", "task_finished", List.of(), "original", null));
        assertThatThrownBy(() -> service.syncBySignTaskId("retired")).hasMessageContaining("无法确认");
        when(gateway.status("retired")).thenReturn(new FadadaSigningGateway.TaskStatus("retired", "task_finished", List.of(), "other-parent", null));
        assertThatThrownBy(() -> service.syncBySignTaskId("retired")).hasMessageContaining("对应关系");
        verify(trade, never()).voidAfterElectronicAbolish(anyLong(), anyInt(), anyLong());
    }
    @ParameterizedTest
    @ValueSource(strings = {"task_finished", "ABOLISH_CREATION_UNCERTAIN", "ABOLISH_sign_progress"})
    void localVoidStaysReadOnlyWithoutQueryingOrChangingTheProvider(String oldProviderStatus) {
        contract.setStatus("VOIDED"); task.setProviderStatus(oldProviderStatus);
        task.setArchivedAt(java.time.LocalDateTime.now());
        task.setLastError("历史作废任务创建失败");
        var membership = mock(com.tradepass.module.contract.service.membership.MembershipService.class);
        ((FadadaContractSigningServiceImpl) service).setMembershipService(membership);
        var result = service.syncCurrent(12L);
        service.syncBySignTaskId("original");
        service.requireCallbackSettled("original", "sign-task-finished");
        assertThat(result.status()).isEqualTo("VOIDED_LOCAL");
        assertThat(result.statusText()).isEqualTo("已作废");
        assertThat(result.canSign()).isFalse();
        assertThat(result.canAbolish()).isFalse();
        assertThat(result.abolishApproved()).isFalse();
        assertThat(result.signedFileArchived()).isTrue();
        assertThat(result.lastError()).isNull();
        assertThat(task.getProviderStatus()).isEqualTo(oldProviderStatus);
        verifyNoInteractions(gateway, intents, membership, trade);
        verify(tasks, never()).updateById(any(FadadaContractSignTaskDO.class));
    }

    @Test void anUnknownHistoricalChildCallbackCannotRewriteALocallyVoidedContract() {
        contract.setStatus("VOIDED"); task.setProviderStatus("task_finished");
        task.setInitiatorSignStatus("signed"); task.setCounterpartySignStatus("signed");
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(null, task);
        when(gateway.status("old-child")).thenReturn(new FadadaSigningGateway.TaskStatus(
                "old-child", "sign_progress", List.of(), "original", null));
        service.syncBySignTaskId("old-child");
        assertThat(task.getProviderStatus()).isEqualTo("task_finished");
        assertThat(task.getInitiatorSignStatus()).isEqualTo("signed");
        assertThat(task.getCounterpartySignStatus()).isEqualTo("signed");
        verifyNoInteractions(intents, trade);
        verify(gateway, never()).status("original");
        verify(tasks, never()).updateById(any(FadadaContractSignTaskDO.class));
        verify(tasks, never()).update(any(FadadaContractSignTaskDO.class), any(Wrapper.class));
    }

}
