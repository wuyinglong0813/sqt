package com.tradepass.module.contract.service.abolish;

import com.tradepass.module.identity.api.permission.AccessControlOperations;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContractAbolishRecoveryServiceTest {
    private final TradeContractMapper contracts = mock(TradeContractMapper.class);
    private final FadadaContractSignTaskMapper tasks = mock(FadadaContractSignTaskMapper.class);
    private final FadadaSigningGateway gateway = mock(FadadaSigningGateway.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ContractAbolishRecoveryService service = new ContractAbolishRecoveryServiceImpl(
            contracts, tasks, gateway, mock(AccessControlOperations.class), jdbc);
    private final FadadaContractSignTaskDO task = new FadadaContractSignTaskDO();

    @BeforeEach void setUp() {
        MybatisTestSupport.initialize(FadadaContractSignTaskDO.class);
        TradeContractDO contract = new TradeContractDO();
        contract.setId(12L); contract.setCompanyId(3L); contract.setCounterpartyCompanyId(4L);
        contract.setVersionNo(1); contract.setStatus("ACTIVE");
        when(contracts.selectByIdForUpdate(12L)).thenReturn(contract);
        task.setId(20L); task.setContractId(12L); task.setVersionNo(1);
        task.setSignTaskId("original"); task.setAbolishedSignTaskId("abolish");
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(task);
        when(tasks.update(any(Wrapper.class))).thenReturn(1);
        when(jdbc.update(anyString(), eq(12L))).thenReturn(1);
        when(gateway.status("original")).thenReturn(status("original", "task_finished"));
        AuthContext.set(8L, 4L);
    }

    @AfterEach void clear() { AuthContext.clear(); }

    @Test void cancelsThenVerifiesBothTasksBeforeUnlocking() {
        when(gateway.status("abolish")).thenReturn(status("abolish", "sign_progress"),
                status("abolish", "task_terminated"));
        service.resumeAfterBilateralApproval(12L);
        var sequence = inOrder(gateway, tasks, jdbc);
        sequence.verify(gateway).status("abolish");
        sequence.verify(gateway).cancel("abolish", "双方同意取消作废并恢复合同履约");
        sequence.verify(gateway).status("abolish");
        sequence.verify(gateway).status("original");
        sequence.verify(gateway).status("abolish");
        sequence.verify(jdbc).update(contains("INSERT INTO fadada_cancelled_abolish_task"),
                eq("abolish"), eq(12L), eq(1), eq("original"));
        sequence.verify(tasks).update(any(Wrapper.class));
        sequence.verify(jdbc).update(contains("UPDATE bilateral_action_request"), eq(12L));
    }

    @ParameterizedTest @ValueSource(strings = {"task_finished", "sign_completed", "revoked"})
    void completedAbolishNeverUnlocks(String status) {
        when(gateway.status("abolish")).thenReturn(status("abolish", status));
        assertThatThrownBy(() -> service.resumeAfterBilateralApproval(12L)).hasMessageContaining("不能恢复");
        verify(gateway, never()).cancel(anyString(), anyString());
        verify(tasks, never()).update(any(Wrapper.class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void completionWinningTheCancellationRaceDoesNotUnlock() {
        when(gateway.status("abolish")).thenReturn(status("abolish", "sign_progress"),
                status("abolish", "task_finished"));
        assertThatThrownBy(() -> service.resumeAfterBilateralApproval(12L)).hasMessageContaining("不能恢复");
        verify(tasks, never()).update(any(Wrapper.class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void cancellationTimeoutKeepsTheContractFrozen() {
        when(gateway.status("abolish")).thenReturn(status("abolish", "sign_progress"));
        doThrow(new IllegalStateException("timeout")).when(gateway).cancel(anyString(), anyString());
        assertThatThrownBy(() -> service.resumeAfterBilateralApproval(12L)).hasMessageContaining("timeout");
        verify(tasks, never()).update(any(Wrapper.class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void unconfirmedCancellationDoesNotUnlock() {
        when(gateway.status("abolish")).thenReturn(status("abolish", "sign_progress"));
        assertThatThrownBy(() -> service.resumeAfterBilateralApproval(12L)).hasMessageContaining("尚未确认终止");
        verify(tasks, never()).update(any(Wrapper.class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void stoppedAbolishCannotRestoreAnAlreadyRevokedOriginal() {
        when(gateway.status("abolish")).thenReturn(status("abolish", "task_terminated"));
        when(gateway.status("original")).thenReturn(status("original", "revoked"));
        assertThatThrownBy(() -> service.resumeAfterBilateralApproval(12L)).hasMessageContaining("原合同");
        verify(tasks, never()).update(any(Wrapper.class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void retryAfterLostLocalResponseRechecksProviderInsteadOfCancellingAgain() {
        when(gateway.status("abolish")).thenReturn(status("abolish", "task_terminated"));
        service.resumeAfterBilateralApproval(12L);
        verify(gateway, never()).cancel(anyString(), anyString());
        verify(gateway).status("original");
        verify(tasks).update(any(Wrapper.class));
    }

    @Test void aNeverStartedAbolishStillRequiresFreshOriginalVerification() {
        task.setAbolishedSignTaskId(null);
        service.resumeAfterBilateralApproval(12L);
        verify(gateway).status("original");
        verify(gateway, never()).cancel(anyString(), anyString());
        verify(tasks).update(any(Wrapper.class));
    }

    @Test void uncertainProviderCreationCannotBeTreatedAsNeverStarted() {
        task.setAbolishedSignTaskId(null); task.setProviderStatus("ABOLISH_CREATION_UNCERTAIN");
        assertThatThrownBy(() -> service.resumeAfterBilateralApproval(12L)).hasMessageContaining("结果尚未确认");
        verifyNoInteractions(gateway);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(tasks, never()).update(any(Wrapper.class));
    }

    private static FadadaSigningGateway.TaskStatus status(String id, String value) {
        return new FadadaSigningGateway.TaskStatus(id, value, List.of());
    }
}
