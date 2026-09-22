package com.tradepass.module.trade.api.ranking;

import com.tradepass.framework.common.pojo.TradePassDtos;

import com.tradepass.framework.common.pojo.TradePassDtos.RankingItem;
import java.util.List;

/** In-process domain contract; implementations retain the original transaction semantics. */
public interface RankingCacheOperations {
    public List<RankingItem> get(long companyId, String direction, String period);

    public void put(long companyId, String direction, String period, List<RankingItem> ranking);

    public void evict(long companyId, String direction);
}
