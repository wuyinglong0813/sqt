package com.tradepass.module.trade.service.ranking;

import com.tradepass.framework.common.pojo.TradePassDtos;
import com.tradepass.module.trade.api.ranking.RankingCacheOperations;
import com.tradepass.module.trade.api.ranking.RankingCacheOperations.*;
import com.tradepass.framework.cache.core.RedisCacheService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.tradepass.framework.common.pojo.TradePassDtos.RankingItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.List;

public interface RankingCacheService {
    List<RankingItem> get(long companyId, String direction, String period);
    void put(long companyId, String direction, String period, List<RankingItem> ranking);
    void evict(long companyId, String direction);
}
