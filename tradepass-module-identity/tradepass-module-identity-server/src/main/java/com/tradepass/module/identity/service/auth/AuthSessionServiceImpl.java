package com.tradepass.module.identity.service.auth;

import com.tradepass.framework.cache.core.RedisCacheService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.module.identity.dal.dataobject.auth.AuthSessionDO;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.module.identity.dal.mysql.auth.AuthSessionMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthSessionServiceImpl implements AuthSessionService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthSessionMapper authSessionMapper;
    private final SysUserMapper sysUserMapper;
    private final long sessionHours;
    private final RedisCacheService redisCache;
    private final Duration authCacheTtl;

    @Autowired
    public AuthSessionServiceImpl(AuthSessionMapper authSessionMapper,
                              SysUserMapper sysUserMapper,
                              @Value("${tradepass.auth.session-hours:168}") long sessionHours,
                              RedisCacheService redisCache,
                              @Value("${tradepass.redis.auth-cache-ttl:60s}") Duration authCacheTtl) {
        this.authSessionMapper = authSessionMapper;
        this.sysUserMapper = sysUserMapper;
        this.sessionHours = sessionHours;
        this.redisCache = redisCache;
        this.authCacheTtl = authCacheTtl;
    }

    AuthSessionServiceImpl(AuthSessionMapper authSessionMapper,
                       SysUserMapper sysUserMapper,
                       long sessionHours) {
        this(authSessionMapper, sysUserMapper, sessionHours, null, Duration.ofSeconds(60));
    }

    public String issue(long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        AuthSessionDO session = new AuthSessionDO();
        session.setTokenHash(hash(token));
        session.setUserId(userId);
        session.setExpiresAt(LocalDateTime.now().plusHours(sessionHours));
        session.setRevoked(false);
        authSessionMapper.insert(session);
        return token;
    }

    public Long resolveUserId(String authorization) {
        String token = extractToken(authorization);
        if (token == null) {
            return null;
        }
        String tokenHash = hash(token);
        Long cachedUserId = cachedUserId(tokenHash);
        if (cachedUserId != null) {
            return cachedUserId;
        }
        AuthSessionDO session = authSessionMapper.selectOne(new LambdaQueryWrapper<AuthSessionDO>()
                .eq(AuthSessionDO::getTokenHash, tokenHash)
                .eq(AuthSessionDO::getRevoked, false)
                .gt(AuthSessionDO::getExpiresAt, LocalDateTime.now())
                .last("LIMIT 1"));
        if (session == null) {
            return null;
        }
        SysUserDO user = sysUserMapper.selectById(session.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            return null;
        }
        Duration remaining = session.getExpiresAt() == null
                ? authCacheTtl
                : Duration.between(LocalDateTime.now(), session.getExpiresAt());
        cacheUserId(tokenHash, user.getId(), shorter(authCacheTtl, remaining));
        return user.getId();
    }

    public void revoke(String authorization) {
        String token = extractToken(authorization);
        if (token == null) {
            return;
        }
        String tokenHash = hash(token);
        if (redisCache != null) {
            redisCache.delete(cacheKey(tokenHash));
        }
        authSessionMapper.update(new LambdaUpdateWrapper<AuthSessionDO>()
                .eq(AuthSessionDO::getTokenHash, tokenHash)
                .set(AuthSessionDO::getRevoked, true));
    }

    private Long cachedUserId(String tokenHash) {
        if (redisCache == null) {
            return null;
        }
        String value = redisCache.get(cacheKey(tokenHash));
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            redisCache.delete(cacheKey(tokenHash));
            return null;
        }
    }

    private void cacheUserId(String tokenHash, long userId, Duration ttl) {
        if (redisCache != null && ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            redisCache.put(cacheKey(tokenHash), String.valueOf(userId), ttl);
        }
    }

    private String cacheKey(String tokenHash) {
        return "auth:session:" + tokenHash;
    }

    private Duration shorter(Duration first, Duration second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.compareTo(second) <= 0 ? first : second;
    }

    private String extractToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        return token.isBlank() ? null : token;
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
