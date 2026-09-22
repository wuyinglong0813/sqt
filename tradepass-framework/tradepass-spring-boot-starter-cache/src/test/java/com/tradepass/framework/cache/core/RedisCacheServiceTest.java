package com.tradepass.framework.cache.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisCacheServiceTest {
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisCacheService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new RedisCacheService(redisTemplate, new ObjectMapper(), true, "tradepass:");
    }

    @Test
    void prefixesKeysAndStoresValuesWithTtl() {
        when(valueOperations.get("tradepass:auth:test")).thenReturn("42");

        assertThat(service.get("auth:test")).isEqualTo("42");
        service.put("auth:test", "7", Duration.ofSeconds(30));

        verify(valueOperations).set("tradepass:auth:test", "7", Duration.ofSeconds(30));
    }

    @Test
    void serializesJsonAndDropsCorruptCachedValues() {
        service.putJson("ranking:test", List.of("甲", "乙"), Duration.ofSeconds(30));
        verify(valueOperations).set(
                "tradepass:ranking:test", "[\"甲\",\"乙\"]", Duration.ofSeconds(30));

        when(valueOperations.get("tradepass:ranking:test")).thenReturn("not-json");
        List<String> result = service.getJson(
                "ranking:test", new TypeReference<List<String>>() {});

        assertThat(result).isNull();
        verify(redisTemplate).delete("tradepass:ranking:test");
    }

    @Test
    void degradesWithoutThrowingWhenRedisIsUnavailable() {
        when(valueOperations.get("tradepass:auth:test"))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(service.get("auth:test")).isNull();
        assertThatCode(() -> service.put("auth:test", "7", Duration.ofSeconds(30)))
                .doesNotThrowAnyException();
    }

    @Test
    void doesNotTouchRedisWhenDisabled() {
        RedisCacheService disabled = new RedisCacheService(
                redisTemplate, new ObjectMapper(), false, "tradepass");

        assertThat(disabled.get("auth:test")).isNull();
        assertThat(disabled.increment("rate:test", Duration.ofMinutes(1))).isNull();
        disabled.put("auth:test", "7", Duration.ofSeconds(30));
        disabled.delete("auth:test");

        verifyNoInteractions(redisTemplate);
    }
}
