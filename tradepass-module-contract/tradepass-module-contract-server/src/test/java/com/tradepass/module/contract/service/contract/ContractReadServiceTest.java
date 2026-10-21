package com.tradepass.module.contract.service.contract;

import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContractReadServiceTest {
    @Test
    void snapshotReadDoesNotAcquireTheWritersContractLock() {
        var mapper = mock(TradeContractMapper.class);
        var jdbc = mock(JdbcTemplate.class);
        var contract = new TradeContractDO();
        contract.setId(12L);
        contract.setStatus("ACTIVE");
        when(mapper.selectById(12L)).thenReturn(contract);
        var service = new ContractReadServiceImpl(mapper, jdbc);

        assertThat(service.selectById(12L).getStatus()).isEqualTo("ACTIVE");

        verify(mapper).selectById(12L);
        verify(mapper, never()).selectByIdForUpdate(any());
        verifyNoInteractions(jdbc);
    }

    @Test
    void writeReadKeepsTheContractLockAndGlobalTransactionRegistration() {
        var mapper = mock(TradeContractMapper.class);
        var jdbc = mock(JdbcTemplate.class);
        var contract = new TradeContractDO();
        contract.setId(12L);
        when(mapper.selectByIdForUpdate(12L)).thenReturn(contract);
        var service = new ContractReadServiceImpl(mapper, jdbc);

        assertThat(service.selectByIdForUpdate(12L).getId()).isEqualTo(12L);

        var order = inOrder(jdbc, mapper);
        order.verify(jdbc).update("UPDATE trade_contract SET created_at = created_at WHERE id = ?", 12L);
        order.verify(mapper).selectByIdForUpdate(12L);
    }
}
