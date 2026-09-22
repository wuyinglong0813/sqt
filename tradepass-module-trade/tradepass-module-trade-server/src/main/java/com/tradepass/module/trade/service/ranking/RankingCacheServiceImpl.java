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

@Service
public class RankingCacheServiceImpl implements RankingCacheService {
    private static final TypeReference<List<RankingItem>> RANKING_LIST_TYPE = new TypeReference<>() {};
    private static final List<String> PERIODS = List.of("total", "year", "month", "last12");

    private final RedisCacheService redisCache;
    private final Duration ttl;

    public RankingCacheServiceImpl(RedisCacheService redisCache,
                               @Value("${tradepass.redis.ranking-cache-ttl:30s}") Duration ttl) {
        this.redisCache = redisCache;
        this.ttl = ttl;
    }

    public List<RankingItem> get(long companyId, String direction, String period) {
        return redisCache.getJson(key(companyId, direction, period), RANKING_LIST_TYPE);
    }

    public void put(long companyId, String direction, String period, List<RankingItem> ranking) {
        redisCache.putJson(key(companyId, direction, period), ranking, ttl);
    }

    public void evict(long companyId, String direction) {
        for (String period : PERIODS) {
            redisCache.delete(key(companyId, direction, period));
        }
    }

    private String key(long companyId, String direction, String period) {
        return "ranking:" + companyId + ":" + direction.toLowerCase() + ":" + period;
    }
}
