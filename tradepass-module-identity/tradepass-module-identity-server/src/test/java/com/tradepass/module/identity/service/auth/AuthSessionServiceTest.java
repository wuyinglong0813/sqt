package com.tradepass.module.identity.service.auth;

import com.tradepass.framework.cache.core.RedisCacheService;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.module.identity.dal.dataobject.auth.AuthSessionDO;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.module.identity.dal.mysql.auth.AuthSessionMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionServiceTest {
    private AuthSessionMapper sessionMapper;
    private SysUserMapper userMapper;
    private AuthSessionService service;

    @BeforeEach
    void setUp() {
        MybatisTestSupport.initialize(AuthSessionDO.class);
        sessionMapper = mock(AuthSessionMapper.class);
        userMapper = mock(SysUserMapper.class);
        service = new AuthSessionServiceImpl(sessionMapper, userMapper, 12L);
    }

    @Test
    void issuesOpaqueTokenAndPersistsOnlyItsHash() {
        String token = service.issue(42L);

        ArgumentCaptor<AuthSessionDO> captor = ArgumentCaptor.forClass(AuthSessionDO.class);
        verify(sessionMapper).insert(captor.capture());
        AuthSessionDO saved = captor.getValue();
        assertThat(token).hasSize(43);
        assertThat(saved.getTokenHash()).hasSize(64).doesNotContain(token);
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getRevoked()).isFalse();
        assertThat(saved.getExpiresAt()).isAfter(saved.getCreatedAt() == null
                ? java.time.LocalDateTime.now().minusMinutes(1)
                : saved.getCreatedAt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesOnlyActiveUserForValidSession() {
        AuthSessionDO session = new AuthSessionDO();
        session.setUserId(42L);
        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(session);
        SysUserDO user = new SysUserDO();
        user.setId(42L);
        user.setStatus("ACTIVE");
        when(userMapper.selectById(42L)).thenReturn(user);

        assertThat(service.resolveUserId("Bearer abc")).isEqualTo(42L);

        user.setStatus("DISABLED");
        assertThat(service.resolveUserId("abc")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlesMissingInvalidAndRevokedSessions() {
        assertThat(service.resolveUserId(null)).isNull();
        assertThat(service.resolveUserId("Bearer ")).isNull();
        verify(sessionMapper, never()).selectOne(any(Wrapper.class));

        when(sessionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThat(service.resolveUserId("expired")).isNull();
    }

    @Test
    void resolvesCachedSessionWithoutQueryingMysql() {
        RedisCacheService redisCache = mock(RedisCacheService.class);
        AuthSessionService cachedService = new AuthSessionServiceImpl(
                sessionMapper, userMapper, 12L, redisCache, Duration.ofSeconds(60));
        when(redisCache.get(anyString())).thenReturn("42");

        assertThat(cachedService.resolveUserId("Bearer cached-token")).isEqualTo(42L);
        verify(sessionMapper, never()).selectOne(any(Wrapper.class));
        verify(userMapper, never()).selectById(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void revokesBearerTokenAndIgnoresBlankAuthorization() {
        service.revoke("Bearer abc");
        verify(sessionMapper).update(any(Wrapper.class));

        service.revoke(" ");
        verify(sessionMapper).update(any(Wrapper.class));
    }
}
