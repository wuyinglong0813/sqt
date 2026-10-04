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

    @Autowired
    public RankingServiceImpl(TradeOrderMapper tradeOrderMapper,
                          CompanyReader companyMapper,
                          AccessControlOperations accessControlService,
                          RankingCacheService rankingCache,
                          ContractDirectoryOperations contractDirectory) {
        this.tradeOrderMapper = tradeOrderMapper;
        this.companyMapper = companyMapper;
        this.accessControlService = accessControlService;
        this.rankingCache = rankingCache;
        this.contractDirectory = contractDirectory;
    }

    public HomePayload supplierHome(String period, String companyId) {
        long cid = accessControlService.resolveCompanyId(companyId);
        return new HomePayload(String.valueOf(cid), loadCompanyName(cid), "SUPPLIER", "我是供应商",
                List.of("year", "month", "last12"), rank("SALE", period, cid),
                contractDirectory.signedTradeContractCounts(cid, "SALE"));
    }

    public HomePayload buyerHome(String period, String companyId) {
        long cid = accessControlService.resolveCompanyId(companyId);
        return new HomePayload(String.valueOf(cid), loadCompanyName(cid), "BUYER", "我是采购商",
                List.of("year", "month", "last12"), rank("PURCHASE", period, cid),
                contractDirectory.signedTradeContractCounts(cid, "PURCHASE"));
    }

    public List<RankingItem> salesRanking(String period, String companyId) {
        return rank("SALE", period, accessControlService.resolveCompanyId(companyId));
    }

    public List<RankingItem> purchaseRanking(String period, String companyId) {
        return rank("PURCHASE", period, accessControlService.resolveCompanyId(companyId));
    }

    private List<RankingItem> rank(String direction, String period, long companyId) {
        String normalizedPeriod = normalizePeriod(period);
        // Cache standalone orders only; signed contracts are read on every refresh so
        // a signature, completion or void immediately changes both parties' statistics.
        List<RankingItem> orders = standaloneOrders(direction, normalizedPeriod, companyId);
        List<RankingItem> contracts = contractDirectory.signedTradeRanking(companyId, direction, normalizedPeriod);
        Map<String, RankingItem> totals = new HashMap<>();
        for (RankingItem item : java.util.stream.Stream.concat(orders.stream(), contracts.stream()).toList()) {
            totals.merge(item.counterpartyName(), item, (a, b) -> new RankingItem(0, a.counterpartyName(),
                    a.amount().add(b.amount()), a.orderCount() + b.orderCount(), "FLAT"));
        }
        List<RankingItem> sorted = totals.values().stream()
                .sorted(Comparator.comparing(RankingItem::amount).reversed()
                        .thenComparing(RankingItem::counterpartyName)).toList();
        List<RankingItem> ranked = new ArrayList<>();
        for (RankingItem item : sorted) {
            ranked.add(new RankingItem(ranked.size() + 1, item.counterpartyName(), item.amount(),
                    item.orderCount(), item.trend()));
        }
        return ranked;
    }

    private List<RankingItem> standaloneOrders(String direction, String normalizedPeriod, long companyId) {
        if (rankingCache != null) {
            List<RankingItem> cached = rankingCache.get(companyId, direction, normalizedPeriod);
            if (cached != null) {
                return cached;
            }
        }
        List<Map<String, Object>> rows = tradeOrderMapper.selectRanking(companyId, direction, normalizedPeriod);
        List<RankingItem> ranked = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> row : rows) {
            ranked.add(new RankingItem(
                    rank++,
                    string(row.get("counterpartyName")),
                    (BigDecimal) row.get("totalAmount"),
                    ((Number) row.get("orderCount")).intValue(),
                    "FLAT"
            ));
        }
        if (rankingCache != null) {
            rankingCache.put(companyId, direction, normalizedPeriod, ranked);
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
