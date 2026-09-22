package com.tradepass.module.trade.api.ranking;

import com.tradepass.framework.common.pojo.TradePassDtos;
import com.tradepass.framework.common.pojo.TradePassDtos.RankingItem;
import java.util.List;
import com.tradepass.module.trade.service.ranking.RankingCacheService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class RankingCacheOperationsImpl implements RankingCacheOperations {
    private final RankingCacheService delegate;
    public RankingCacheOperationsImpl(@Lazy RankingCacheService delegate) { this.delegate = delegate; }
    @Override public List<RankingItem> get(long companyId, String direction, String period) { return delegate.get(companyId, direction, period); }
    @Override public void put(long companyId, String direction, String period, List<RankingItem> ranking) { delegate.put(companyId, direction, period, ranking); }
    @Override public void evict(long companyId, String direction) { delegate.evict(companyId, direction); }
}
