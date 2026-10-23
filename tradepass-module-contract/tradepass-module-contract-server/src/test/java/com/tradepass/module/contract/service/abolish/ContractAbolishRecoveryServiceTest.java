package com.tradepass.module.contract.service.abolish;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ContractAbolishRecoveryServiceTest {
    private final TradeContractMapper contracts = mock(TradeContractMapper.class);
    private final FadadaContractSignTaskMapper tasks = mock(FadadaContractSignTaskMapper.class);
    private final FadadaSigningGateway gateway = mock(FadadaSigningGateway.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ContractAbolishRecoveryService service = new ContractAbolishRecoveryServiceImpl(
            contracts, tasks, gateway, mock(AccessControlOperations.class), jdbc);

    @AfterEach void clear() { AuthContext.clear(); }

    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "VOIDED"})
    void retiredRecoveryCannotCancelProviderTasksOrReopenLocalContracts(String status) {
        TradeContractDO contract = new TradeContractDO();
        contract.setCompanyId(3L); contract.setCounterpartyCompanyId(4L); contract.setStatus(status);
        when(contracts.selectById(12L)).thenReturn(contract);
        AuthContext.set(8L, 4L);
        assertThatThrownBy(() -> service.resumeAfterBilateralApproval(12L)).hasMessageContaining("不能恢复履约");
        verifyNoInteractions(tasks, gateway, jdbc);
    }

    @Test void retiredRecoveryStillChecksTheContractParty() {
        TradeContractDO contract = new TradeContractDO();
        contract.setCompanyId(3L); contract.setCounterpartyCompanyId(4L);
        when(contracts.selectById(12L)).thenReturn(contract);
        AuthContext.set(8L, 5L);
        assertThatThrownBy(() -> service.resumeAfterBilateralApproval(12L)).hasMessageContaining("合同不存在");
        verifyNoInteractions(tasks, gateway, jdbc);
    }
}
