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
import java.math.BigDecimal;
import com.tradepass.framework.common.pojo.TradePassDtos.RankingItem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContractDirectoryServiceTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeContractMapping() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "directory-test"),
                TradeContractDO.class);
    }

    @Test
    void tradeRankingResolvesIncomingPartnerNamesWithoutLoadingContractContent() {
        TradeContractMapper contracts = mock(TradeContractMapper.class);
        IdentityDirectoryOperations identity = mock(IdentityDirectoryOperations.class);
        when(contracts.selectSignedTradeRanking(4L, "PURCHASE", "year")).thenReturn(List.of(
                Map.of("counterpartyCompanyId", 3L, "totalAmount", new BigDecimal("27000"), "orderCount", 4)));
        when(identity.companyNames(List.of(3L))).thenReturn(Map.of(3L, "供方企业"));
        var service = new ContractDirectoryServiceImpl(contracts, identity, mock(JdbcTemplate.class));

        assertThat(service.signedTradeRanking(4L, "PURCHASE", "year"))
                .containsExactly(new RankingItem(0, "供方企业", new BigDecimal("27000"), 4, "FLAT"));
    }

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

    @Test @SuppressWarnings("unchecked") void sortsByActualSalesBeforeApplyingPagination() {
        TradeContractMapper contracts = mock(TradeContractMapper.class);
        var identity = mock(IdentityDirectoryOperations.class); var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(3L), eq(3L))).thenReturn(List.of(3L));
        when(identity.companyNames(any())).thenReturn(Map.of(3L, "本公司"));
        var newest = new TradeContractDO(); newest.setId(1L); newest.setAmount(new BigDecimal("90000"));
        var older = new TradeContractDO(); older.setId(2L); older.setAmount(new BigDecimal("100"));
        when(contracts.selectList(any(Wrapper.class))).thenReturn(List.of(newest, older));
        var performance = mock(com.tradepass.module.trade.api.ranking.SalesPerformanceOperations.class);
        when(performance.contractSales(3L)).thenReturn(Map.of(
                "1", new com.tradepass.module.trade.api.ranking.SalesPerformanceOperations.ContractSales(new BigDecimal("20"), BigDecimal.ZERO, 1),
                "2", new com.tradepass.module.trade.api.ranking.SalesPerformanceOperations.ContractSales(new BigDecimal("100"), BigDecimal.ZERO, 2)));
        var service = new ContractDirectoryServiceImpl(contracts, identity, jdbc);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sales", performance);
        assertThat(service.partyContracts(3L, null, null, 1, 0)).extracting(TradeContractDO::getId).containsExactly(2L);
        assertThat(service.partyContracts(3L, null, null, 1, 1)).extracting(TradeContractDO::getId).containsExactly(1L);
        assertThat(service.partySalesAmount(3L)).isEqualByComparingTo("120");
    }
}
