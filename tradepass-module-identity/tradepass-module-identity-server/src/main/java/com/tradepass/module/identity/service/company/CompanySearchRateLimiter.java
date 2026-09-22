package com.tradepass.module.identity.service.company;

import com.tradepass.framework.cache.core.RedisCacheService;

import com.tradepass.framework.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Redis 共享窗口限流；Redis 不可用时回退到单实例内存窗口。 */
@Component
public class CompanySearchRateLimiter {
    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

    private final int maxRequestsPerMinute;
    private final RedisCacheService redisCache;
    private final ConcurrentMap<Long, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public CompanySearchRateLimiter(
            @Value("${tradepass.security.company-search.max-requests-per-minute:30}") int maxRequestsPerMinute,
            RedisCacheService redisCache) {
        this.maxRequestsPerMinute = Math.max(1, maxRequestsPerMinute);
        this.redisCache = redisCache;
    }

    CompanySearchRateLimiter(int maxRequestsPerMinute) {
        this.maxRequestsPerMinute = Math.max(1, maxRequestsPerMinute);
        this.redisCache = null;
    }

    public void check(long userId) {
        if (redisCache != null) {
            long minuteBucket = Instant.now().getEpochSecond() / 60;
            Long requestCount = redisCache.increment(
                    "rate-limit:company-search:" + userId + ":" + minuteBucket,
                    Duration.ofMinutes(2));
            if (requestCount != null) {
                if (requestCount > maxRequestsPerMinute) {
                    throw rateLimitExceeded();
                }
                return;
            }
        }
        checkLocally(userId);
    }

    private void checkLocally(long userId) {
        long now = System.nanoTime();
        windows.compute(userId, (ignored, current) -> {
            if (current == null || now - current.startedAtNanos() >= WINDOW_NANOS) {
                return new Window(now, 1);
            }
            if (current.requestCount() >= maxRequestsPerMinute) {
                throw rateLimitExceeded();
            }
            return new Window(current.startedAtNanos(), current.requestCount() + 1);
        });
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAtNanos() >= WINDOW_NANOS);
        }
    }

    private BusinessException rateLimitExceeded() {
        return new BusinessException("企业搜索过于频繁，请稍后再试");
    }

    private record Window(long startedAtNanos, int requestCount) {
    }
}
