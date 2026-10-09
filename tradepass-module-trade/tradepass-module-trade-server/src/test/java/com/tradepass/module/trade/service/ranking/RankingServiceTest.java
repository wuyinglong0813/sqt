package com.tradepass.module.trade.service.ranking;

import com.tradepass.framework.common.pojo.TradePassDtos;

import com.tradepass.module.identity.api.permission.AccessControlOperations;

import com.tradepass.framework.common.pojo.TradePassDtos.HomePayload;
import com.tradepass.framework.common.pojo.TradePassDtos.RankingItem;
import com.tradepass.framework.common.pojo.TradePassDtos.CounterpartyContractCount;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import com.tradepass.module.trade.dal.mysql.order.TradeOrderMapper;
import com.tradepass.module.contract.api.directory.ContractDirectoryOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RankingServiceTest {
    private TradeOrderMapper orderMapper;
    private CompanyReader companyMapper;
    private AccessControlOperations accessControl;
    private ContractDirectoryOperations contracts;
    private RankingService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(TradeOrderMapper.class);
        companyMapper = mock(CompanyReader.class);
        accessControl = mock(AccessControlOperations.class);
        contracts = mock(ContractDirectoryOperations.class);
        service = new RankingServiceImpl(orderMapper, companyMapper, accessControl, null, contracts);
        when(accessControl.resolveCompanyId("3")).thenReturn(3L);
    }

    @Test
    void buildsSupplierHomeAndRanksRows() {
        CompanyRespDTO company = new CompanyRespDTO();
        company.setName("测试企业");
        when(companyMapper.selectById(3L)).thenReturn(company);
        when(orderMapper.selectRanking(3L, "SALE", "month")).thenReturn(List.of(
                Map.of("counterpartyName", "甲方", "totalAmount", new BigDecimal("12.50"), "orderCount", 2),
                Map.of("counterpartyName", "乙方", "totalAmount", new BigDecimal("7.00"), "orderCount", 1)
        ));

        HomePayload result = service.supplierHome("MONTH", "3");

        assertThat(result.companyName()).isEqualTo("测试企业");
        assertThat(result.role()).isEqualTo("SUPPLIER");
        assertThat(result.ranking()).extracting(RankingItem::rank).containsExactly(1, 2);
        assertThat(result.ranking().get(0).counterpartyName()).isEqualTo("甲方");
    }

    @Test
    void normalizesUnknownPeriodAndHandlesMissingCompany() {
        when(orderMapper.selectRanking(3L, "PURCHASE", "year")).thenReturn(List.of());

        HomePayload result = service.buyerHome("quarter", "3");

        assertThat(result.companyName()).isEqualTo("未知企业");
        assertThat(result.ranking()).isEmpty();
        verify(orderMapper).selectRanking(3L, "PURCHASE", "year");
    }

    @Test
    void readsConfirmedDocumentsInsteadOfObsoleteContractCache() {
        RankingCacheService rankingCache = mock(RankingCacheService.class);
        RankingService cachedService = new RankingServiceImpl(
                orderMapper, companyMapper, accessControl, rankingCache, contracts);
        List<RankingItem> cached = List.of(
                new RankingItem(1, "缓存客户", new BigDecimal("99.00"), 3, "FLAT"));
        when(rankingCache.get(3L, "SALE", "month")).thenReturn(cached);
        when(orderMapper.selectRanking(3L, "SALE", "month")).thenReturn(List.of(
                Map.of("counterpartyName", "实际客户", "totalAmount", new BigDecimal("12.50"), "orderCount", 1)));

        assertThat(cachedService.salesRanking("month", "3").get(0).amount()).isEqualByComparingTo("12.50");
        verify(orderMapper).selectRanking(3L, "SALE", "month");
        verify(rankingCache, never()).get(3L, "SALE", "month");
    }

    @Test
    void contractsWithoutConfirmedSalesDoNotContributeToPurchaseFigures() {
        when(contracts.signedTradeRanking(3L, "PURCHASE", "year")).thenReturn(List.of(
                new RankingItem(0, "供方甲", new BigDecimal("27000.00"), 1, "FLAT"),
                new RankingItem(0, "供方乙", new BigDecimal("200.00"), 1, "FLAT"),
                new RankingItem(0, "供方丙", new BigDecimal("300.00"), 1, "FLAT"),
                new RankingItem(0, "供方丁", new BigDecimal("400.00"), 1, "FLAT")));

        var ranking = service.buyerHome("year", "3").ranking();

        assertThat(ranking).isEmpty();
        verify(contracts, never()).signedTradeRanking(3L, "PURCHASE", "year");
    }

    @Test
    void neverAddsNominalContractAmountsToSalesDocumentAmounts() {
        when(orderMapper.selectRanking(3L, "SALE", "last12")).thenReturn(List.of(
                Map.of("counterpartyName", "客户甲", "totalAmount", new BigDecimal("100"), "orderCount", 1),
                Map.of("counterpartyName", "客户乙", "totalAmount", new BigDecimal("200"), "orderCount", 2)));
        when(contracts.signedTradeRanking(3L, "SALE", "last12")).thenReturn(List.of(
                new RankingItem(0, "客户甲", new BigDecimal("300"), 1, "FLAT")));

        var ranking = service.salesRanking("last12", "3");

        assertThat(ranking).extracting(RankingItem::counterpartyName).containsExactly("客户乙", "客户甲");
        assertThat(ranking.get(0).amount()).isEqualByComparingTo("200");
        assertThat(ranking.get(0).orderCount()).isEqualTo(2);
        assertThat(ranking.get(1).rank()).isEqualTo(2);
    }

    @Test
    void homeIncludesLifetimeContractCountsIndependentlyOfTheRankingPeriod() {
        var counts = List.of(new CounterpartyContractCount("2098123456789012345", 8));
        when(contracts.signedTradeContractCounts(3L, "PURCHASE")).thenReturn(counts);

        assertThat(service.buyerHome("month", "3").partnerContractCounts()).isEqualTo(counts);
        assertThat(service.buyerHome("year", "3").partnerContractCounts()).isEqualTo(counts);
        assertThat(service.supplierHome("year", "3").partnerContractCounts()).isEmpty();
        verify(contracts).signedTradeContractCounts(3L, "SALE");
    }

    @Test
    void refreshingFiguresImmediatelyReflectsConfirmedAndVoidedDocuments() {
        RankingCacheService cache = mock(RankingCacheService.class);
        RankingService cached = new RankingServiceImpl(orderMapper, companyMapper, accessControl, cache, contracts);
        when(cache.get(3L, "PURCHASE", "year")).thenReturn(List.of());
        when(orderMapper.selectRanking(3L, "PURCHASE", "year")).thenReturn(List.of(),
                List.of(Map.of("counterpartyName", "供方甲", "totalAmount", new BigDecimal("27000"), "orderCount", 1)), List.of());

        assertThat(cached.buyerHome("year", "3").ranking()).isEmpty();
        assertThat(cached.buyerHome("year", "3").ranking().get(0).orderCount()).isEqualTo(1);
        assertThat(cached.buyerHome("year", "3").ranking()).isEmpty();
        verify(orderMapper, org.mockito.Mockito.times(3)).selectRanking(3L, "PURCHASE", "year");
    }

    @Test void equallyNamedCompaniesRemainSeparate() {
        when(orderMapper.selectRanking(3L, "SALE", "year")).thenReturn(List.of(
                Map.of("counterpartyCompanyId", 4L, "counterpartyName", "同名企业", "totalAmount", new BigDecimal("10"), "orderCount", 1),
                Map.of("counterpartyCompanyId", 5L, "counterpartyName", "同名企业", "totalAmount", new BigDecimal("20"), "orderCount", 1)));
        var ranking = service.salesRanking("year", "3");
        assertThat(ranking).hasSize(2);
        assertThat(ranking).extracting(RankingItem::counterpartyCompanyId).containsExactly("5", "4");
    }

    @Test void supplierHomeIncludesRetailSalesAndSeparatesReturns() {
        var retail = mock(com.tradepass.module.trade.service.retail.RetailService.class);
        when(retail.summary(3L, "month")).thenReturn(Map.of("salesAmount", new BigDecimal("20"),
                "returnAmount", new BigDecimal("2"), "salesOrderCount", 1, "customerCount", 3));
        when(orderMapper.selectRanking(3L, "SALE", "month")).thenReturn(List.of(
                Map.of("counterpartyName", "合作客户", "totalAmount", new BigDecimal("80"), "orderCount", 2)));
        when(orderMapper.selectSalesTotals(3L, "SALE", "month")).thenReturn(Map.of("returnAmount", new BigDecimal("3")));
        var service = new RankingServiceImpl(orderMapper, companyMapper, accessControl, null, contracts, retail);
        var stats = service.supplierHome("MONTH", "3").stats();
        assertThat((BigDecimal) stats.get("totalAmount")).isEqualByComparingTo("100");
        assertThat((BigDecimal) stats.get("netSalesAmount")).isEqualByComparingTo("95");
        assertThat(stats.get("totalOrders")).isEqualTo(3);
        assertThat(stats.get("retailCustomerCount")).isEqualTo(3);
        service.buyerHome("month", "3");
        verify(retail, org.mockito.Mockito.times(1)).summary(3L, "month");
    }
}
