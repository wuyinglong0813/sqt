package com.tradepass.module.trade.service.ranking;

import com.tradepass.framework.common.pojo.TradePassDtos;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;

import com.tradepass.framework.common.pojo.TradePassDtos.HomePayload;
import com.tradepass.framework.common.pojo.TradePassDtos.RankingItem;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import com.tradepass.module.trade.dal.mysql.order.TradeOrderMapper;
import com.tradepass.module.contract.api.directory.ContractDirectoryOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;

@Service
public class RankingServiceImpl implements RankingService {
    private final TradeOrderMapper tradeOrderMapper;
    private final CompanyReader companyMapper;
    private final AccessControlOperations accessControlService;
    private final RankingCacheService rankingCache;
    private final ContractDirectoryOperations contractDirectory;
    private final com.tradepass.module.trade.service.retail.RetailService retail;

    @Autowired
    public RankingServiceImpl(TradeOrderMapper tradeOrderMapper,
                          CompanyReader companyMapper,
                          AccessControlOperations accessControlService,
                          RankingCacheService rankingCache,
                          ContractDirectoryOperations contractDirectory,
                          com.tradepass.module.trade.service.retail.RetailService retail) {
        this.tradeOrderMapper = tradeOrderMapper;
        this.companyMapper = companyMapper;
        this.accessControlService = accessControlService;
        this.rankingCache = rankingCache;
        this.contractDirectory = contractDirectory;
        this.retail = retail;
    }

    public RankingServiceImpl(TradeOrderMapper orders, CompanyReader companies, AccessControlOperations access,
                       RankingCacheService cache, ContractDirectoryOperations contracts) {
        this(orders, companies, access, cache, contracts, null);
    }

    public HomePayload supplierHome(String period, String companyId) {
        long cid = accessControlService.resolveCompanyId(companyId);
        return home(cid, "SALE", period);
    }

    public HomePayload buyerHome(String period, String companyId) {
        long cid = accessControlService.resolveCompanyId(companyId);
        return home(cid, "PURCHASE", period);
    }

    private HomePayload home(long cid, String direction, String period) {
        String normalized = normalizePeriod(period);
        List<RankingItem> ranking = rank(direction, normalized, cid);
        BigDecimal partnerAmount = ranking.stream().map(RankingItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        var totals = tradeOrderMapper.selectSalesTotals(cid, direction, normalized);
        BigDecimal partnerReturn = totals == null ? BigDecimal.ZERO : (BigDecimal) totals.getOrDefault("returnAmount", BigDecimal.ZERO);
        var retailTotals = retail != null && "SALE".equals(direction) ? retail.summary(cid, normalized) : Map.<String, Object>of();
        BigDecimal retailAmount = new BigDecimal(String.valueOf(retailTotals.getOrDefault("salesAmount", 0)));
        BigDecimal returned = partnerReturn.add(new BigDecimal(String.valueOf(retailTotals.getOrDefault("returnAmount", 0))));
        var stats = new java.util.LinkedHashMap<String, Object>();
        stats.put("totalAmount", partnerAmount.add(retailAmount)); stats.put("partnerAmount", partnerAmount);
        stats.put("retailAmount", retailAmount); stats.put("returnAmount", returned);
        stats.put("netSalesAmount", partnerAmount.add(retailAmount).subtract(returned));
        stats.put("totalOrders", ranking.stream().mapToInt(RankingItem::orderCount).sum()
                + Integer.parseInt(String.valueOf(retailTotals.getOrDefault("salesOrderCount", 0))));
        stats.put("retailCustomerCount", retailTotals.getOrDefault("customerCount", 0));
        return new HomePayload(String.valueOf(cid), loadCompanyName(cid), "SALE".equals(direction) ? "SUPPLIER" : "BUYER",
                "SALE".equals(direction) ? "我是供应商" : "我是采购商", List.of("year", "month", "last12"), ranking,
                contractDirectory.signedTradeContractCounts(cid, direction), stats);
    }

    public List<RankingItem> salesRanking(String period, String companyId) {
        return rank("SALE", period, accessControlService.resolveCompanyId(companyId));
    }

    public List<RankingItem> purchaseRanking(String period, String companyId) {
        return rank("PURCHASE", period, accessControlService.resolveCompanyId(companyId));
    }

    private List<RankingItem> rank(String direction, String period, long companyId) {
        String normalizedPeriod = normalizePeriod(period);
        // Read confirmed sales documents live. Contract prices never contribute to sales figures.
        List<RankingItem> orders = standaloneOrders(direction, normalizedPeriod, companyId);
        Map<String, RankingItem> totals = new HashMap<>();
        for (RankingItem item : orders) {
            String key = item.counterpartyCompanyId() == null ? "name:" + item.counterpartyName() : "company:" + item.counterpartyCompanyId();
            totals.merge(key, item, (a, b) -> new RankingItem(0, a.counterpartyName(),
                    a.amount().add(b.amount()), a.orderCount() + b.orderCount(), "FLAT", a.counterpartyCompanyId()));
        }
        List<RankingItem> sorted = totals.values().stream()
                .sorted(Comparator.comparing(RankingItem::amount).reversed()
                        .thenComparing(RankingItem::counterpartyName)).toList();
        List<RankingItem> ranked = new ArrayList<>();
        for (RankingItem item : sorted) {
            ranked.add(new RankingItem(ranked.size() + 1, item.counterpartyName(), item.amount(),
                    item.orderCount(), item.trend(), item.counterpartyCompanyId()));
        }
        return ranked;
    }

    private List<RankingItem> standaloneOrders(String direction, String normalizedPeriod, long companyId) {
        List<Map<String, Object>> rows = tradeOrderMapper.selectRanking(companyId, direction, normalizedPeriod);
        List<RankingItem> ranked = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> row : rows) {
            String name = string(row.get("counterpartyName"));
            if (row.get("counterpartyCompanyId") instanceof Number id) {
                var company = companyMapper.selectById(id.longValue());
                if (company != null) name = company.getName();
            }
            ranked.add(new RankingItem(
                    rank++,
                    name == null || name.isBlank() ? "未知企业" : name,
                    (BigDecimal) row.get("totalAmount"),
                    ((Number) row.get("orderCount")).intValue(),
                    "FLAT", row.get("counterpartyCompanyId") == null ? null : String.valueOf(row.get("counterpartyCompanyId"))
            ));
        }
        return ranked;
    }

    private String loadCompanyName(long companyId) {
        CompanyRespDTO company = companyMapper.selectById(companyId);
        return company == null ? "未知企业" : company.getName();
    }

    private String normalizePeriod(String period) {
        if (period == null || period.isBlank()) {
            return "year";
        }
        String normalized = period.toLowerCase();
        return List.of("year", "month", "last12").contains(normalized) ? normalized : "year";
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
