package com.tradepass.module.identity.service.company;

import com.tradepass.framework.cache.core.RedisCacheService;

import com.tradepass.framework.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompanySearchRateLimiterTest {
    @Test
    void limitsEachAuthenticatedUserIndependently() {
        CompanySearchRateLimiter limiter = new CompanySearchRateLimiter(2);

        assertThatCode(() -> limiter.check(7L)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.check(7L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("企业搜索过于频繁，请稍后再试");
        assertThatCode(() -> limiter.check(8L)).doesNotThrowAnyException();
    }

    @Test
    void usesSharedRedisCounterWhenAvailable() {
        RedisCacheService redisCache = mock(RedisCacheService.class);
        CompanySearchRateLimiter limiter = new CompanySearchRateLimiter(2, redisCache);
        when(redisCache.increment(anyString(), eq(Duration.ofMinutes(2))))
                .thenReturn(1L, 2L, 3L);

        assertThatCode(() -> limiter.check(7L)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.check(7L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("企业搜索过于频繁，请稍后再试");
    }
}
