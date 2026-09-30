package com.tradepass.module.contract.service.directory;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.dal.mysql.signing.ContractSigningTodoSql;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContractDirectoryServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void partyListHidesPendingContractsUntilTheInitiatorSigns() {
        TradeContractMapper contracts = mock(TradeContractMapper.class);
        IdentityDirectoryOperations identity = mock(IdentityDirectoryOperations.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(4L), eq(4L))).thenReturn(List.of(3L, 4L));
        when(identity.companyNames(any())).thenReturn(Map.of(3L, "A公司", 4L, "B公司"));
        when(contracts.selectList(any(Wrapper.class))).thenReturn(List.of());
        ContractDirectoryServiceImpl service = new ContractDirectoryServiceImpl(contracts, identity, jdbc);

        service.partyContracts(4L, null, "PENDING", 20, 0);

        var captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(contracts).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains(ContractSigningTodoSql.COUNTERPARTY_RELEASED.trim());
        assertThat(captor.getValue().getSqlSegment()).doesNotContain("NULLIF(direction");
    }

    @Test
    @SuppressWarnings("unchecked")
    void partyListKeepsOnlyTheViewerTradeDirection() {
        TradeContractMapper contracts = mock(TradeContractMapper.class);
        IdentityDirectoryOperations identity = mock(IdentityDirectoryOperations.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(3L), eq(3L))).thenReturn(List.of(3L));
        when(identity.companyNames(any())).thenReturn(Map.of(3L, "采购企业"));
        when(contracts.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(contracts.selectCount(any(Wrapper.class))).thenReturn(0L);
        ContractDirectoryServiceImpl service = new ContractDirectoryServiceImpl(contracts, identity, jdbc);

        service.partyContracts(3L, "供应企业", null, "SALE", 20, 0);
        service.partyContractCount(3L, "供应企业", null, "PURCHASE");

        var captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(contracts).selectList(captor.capture());
        verify(contracts).selectCount(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(query ->
                assertThat(query.getSqlSegment()).contains("NULLIF(direction"));
        assertThat(parameters(captor.getAllValues().get(0))).containsValue("SALE").doesNotContainValue("PURCHASE");
        assertThat(parameters(captor.getAllValues().get(1))).containsValue("PURCHASE").doesNotContainValue("SALE");
    }

    private static Map<String, Object> parameters(Wrapper<?> query) {
        return ((AbstractWrapper<?, ?, ?>) query).getParamNameValuePairs();
    }
}
