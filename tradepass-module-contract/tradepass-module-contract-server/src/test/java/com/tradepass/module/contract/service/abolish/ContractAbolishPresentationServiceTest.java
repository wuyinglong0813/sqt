package com.tradepass.module.contract.service.abolish;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.trade.api.bilateral.BilateralStateOperations;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContractAbolishPresentationServiceTest {
    @Test @SuppressWarnings("unchecked")
    void listsShowTheCurrentVersionAbolishProgressWithoutChangingLegalStatus() {
        MybatisTestSupport.initialize(FadadaContractSignTaskDO.class);
        var tasks = mock(FadadaContractSignTaskMapper.class);
        var bilateral = mock(BilateralStateOperations.class);
        when(bilateral.approvedVoidContracts(any())).thenReturn(List.of(1L, 2L, 3L));
        when(tasks.selectList(any(Wrapper.class))).thenReturn(List.of(
                task(1L, 1, "old-child", "ABOLISH_sign_progress"),
                task(2L, 2, "child", "ABOLISH_sign_progress"),
                task(3L, 2, "stopped-child", "ABOLISH_task_terminated")));
        var contracts = List.of(contract(1L, "ACTIVE"), contract(2L, "ACTIVE"),
                contract(3L, "ACTIVE"), contract(4L, "VOIDED"));
        var service = new ContractAbolishPresentationService(tasks, bilateral);
        assertThat(service.statusTexts(contracts)).isEqualTo(Map.of(
                1L, "待签署作废协议", 2L, "作废签署中", 3L, "作废签署已中止"));
        assertThat(contracts.get(0).getStatus()).isEqualTo("ACTIVE");
        verify(bilateral, times(1)).approvedVoidContracts(any());
        verify(tasks, times(1)).selectList(any(Wrapper.class));
    }

    @Test void terminalContractsNeverKeepAnOldAbolishProgressLabel() {
        var tasks = mock(FadadaContractSignTaskMapper.class);
        var bilateral = mock(BilateralStateOperations.class);
        var service = new ContractAbolishPresentationService(tasks, bilateral);
        assertThat(service.statusTexts(List.of(contract(1L, "VOIDED"), contract(2L, "COMPLETED")))).isEmpty();
        verifyNoInteractions(tasks, bilateral);
    }

    private TradeContractDO contract(Long id, String status) {
        var result = new TradeContractDO(); result.setId(id); result.setStatus(status); result.setVersionNo(2); return result;
    }

    private FadadaContractSignTaskDO task(Long contractId, int version, String child, String status) {
        var result = new FadadaContractSignTaskDO(); result.setContractId(contractId); result.setVersionNo(version);
        result.setAbolishedSignTaskId(child); result.setProviderStatus(status); return result;
    }
}
