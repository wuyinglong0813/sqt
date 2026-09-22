package com.tradepass.module.trade.service.ranking;

import com.tradepass.framework.common.pojo.TradePassDtos;

import com.tradepass.module.identity.api.permission.AccessControlOperations;

import com.tradepass.framework.common.pojo.TradePassDtos.HomePayload;
import com.tradepass.framework.common.pojo.TradePassDtos.RankingItem;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import com.tradepass.module.trade.dal.mysql.order.TradeOrderMapper;
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
    private RankingService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(TradeOrderMapper.class);
        companyMapper = mock(CompanyReader.class);
        accessControl = mock(AccessControlOperations.class);
        service = new RankingServiceImpl(orderMapper, companyMapper, accessControl);
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
    void returnsCachedRankingWithoutRunningAggregateQuery() {
        RankingCacheService rankingCache = mock(RankingCacheService.class);
        RankingService cachedService = new RankingServiceImpl(
                orderMapper, companyMapper, accessControl, rankingCache);
        List<RankingItem> cached = List.of(
                new RankingItem(1, "缓存客户", new BigDecimal("99.00"), 3, "FLAT"));
        when(rankingCache.get(3L, "SALE", "month")).thenReturn(cached);

        assertThat(cachedService.salesRanking("month", "3")).isEqualTo(cached);
        verify(orderMapper, never()).selectRanking(3L, "SALE", "month");
    }
}
