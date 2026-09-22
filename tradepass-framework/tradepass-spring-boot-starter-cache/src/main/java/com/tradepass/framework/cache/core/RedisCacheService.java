package com.tradepass.framework.cache.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 的容错访问层。Redis 只承担缓存和协调职责，访问失败时业务服务会回退到数据库或本地实现。
 */
@Service
public class RedisCacheService {
    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);
    private static final long WARNING_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String keyPrefix;
    private final AtomicLong lastWarningAt = new AtomicLong(0);

    public RedisCacheService(StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper,
                             @Value("${tradepass.redis.enabled:true}") boolean enabled,
                             @Value("${tradepass.redis.key-prefix:tradepass}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.keyPrefix = normalizePrefix(keyPrefix);
    }

    public String get(String suffix) {
        if (!enabled) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(key(suffix));
        } catch (RuntimeException error) {
            warnOnce("读取", error);
            return null;
        }
    }

    public void put(String suffix, String value, Duration ttl) {
        if (!enabled || value == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(suffix), value, ttl);
        } catch (RuntimeException error) {
            warnOnce("写入", error);
        }
    }

    public void delete(String suffix) {
        if (!enabled) {
            return;
        }
        try {
            redisTemplate.delete(key(suffix));
        } catch (RuntimeException error) {
            warnOnce("删除", error);
        }
    }

    public Long increment(String suffix, Duration ttl) {
        if (!enabled || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return null;
        }
        try {
            long seconds = Math.max(1, ttl.toSeconds());
            return redisTemplate.execute(
                    INCREMENT_WITH_TTL,
                    List.of(key(suffix)),
                    String.valueOf(seconds));
        } catch (RuntimeException error) {
            warnOnce("计数", error);
            return null;
        }
    }

    public <T> T getJson(String suffix, TypeReference<T> type) {
        String json = get(suffix);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception error) {
            delete(suffix);
            warnOnce("反序列化", error);
            return null;
        }
    }

    public void putJson(String suffix, Object value, Duration ttl) {
        if (!enabled || value == null) {
            return;
        }
        try {
            put(suffix, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception error) {
            warnOnce("序列化", error);
        }
    }

    private String key(String suffix) {
        return keyPrefix + ":" + suffix;
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "tradepass";
        }
        String normalized = prefix.trim();
        while (normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? "tradepass" : normalized;
    }

    private void warnOnce(String operation, Exception error) {
        long now = System.currentTimeMillis();
        long previous = lastWarningAt.get();
        if (now - previous >= WARNING_INTERVAL_MILLIS && lastWarningAt.compareAndSet(previous, now)) {
            log.warn("Redis {}失败，已自动使用降级路径: {}", operation, error.getMessage());
        } else {
            log.debug("Redis {}失败: {}", operation, error.getMessage());
        }
    }
}
