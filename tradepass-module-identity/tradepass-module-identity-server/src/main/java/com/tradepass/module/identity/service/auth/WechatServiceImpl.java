package com.tradepass.module.identity.service.auth;

import com.tradepass.framework.cache.core.RedisCacheService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
public class WechatServiceImpl implements WechatService {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final URI CLOUD_PHONE_URI = URI.create(
            "http://api.weixin.qq.com/wxa/business/getuserphonenumber");

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String wechatAppId;
    private final String wechatAppSecret;
    private final boolean devEnabled;
    private final boolean cloudOpenApiEnabled;
    private final RedisCacheService redisCache;
    private final Duration localCacheTtl;
    private volatile String cachedAccessToken;
    private volatile Instant accessTokenExpiresAt = Instant.EPOCH;

    @Autowired
    public WechatServiceImpl(@Value("${wechat.app-id}") String wechatAppId,
                         @Value("${wechat.app-secret}") String wechatAppSecret,
                         @Value("${tradepass.dev.enabled:false}") boolean devEnabled,
                         @Value("${wechat.cloud-open-api-enabled:false}") boolean cloudOpenApiEnabled,
                         RedisCacheService redisCache,
                         @Value("${tradepass.redis.wechat-local-cache-ttl:5m}") Duration localCacheTtl) {
        this(wechatAppId, wechatAppSecret, devEnabled, cloudOpenApiEnabled, redisCache,
                localCacheTtl, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    WechatServiceImpl(String wechatAppId, String wechatAppSecret, boolean devEnabled) {
        this(wechatAppId, wechatAppSecret, devEnabled, false, null, Duration.ofMinutes(5),
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    WechatServiceImpl(String wechatAppId, String wechatAppSecret, boolean devEnabled,
                  RedisCacheService redisCache, Duration localCacheTtl) {
        this(wechatAppId, wechatAppSecret, devEnabled, false, redisCache, localCacheTtl,
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    WechatServiceImpl(String wechatAppId, String wechatAppSecret, boolean devEnabled,
                  boolean cloudOpenApiEnabled, RedisCacheService redisCache,
                  Duration localCacheTtl, HttpClient httpClient) {
        this.wechatAppId = wechatAppId;
        this.wechatAppSecret = wechatAppSecret;
        this.devEnabled = devEnabled;
        this.cloudOpenApiEnabled = cloudOpenApiEnabled;
        this.redisCache = redisCache;
        this.localCacheTtl = localCacheTtl;
        this.httpClient = httpClient;
        this.mapper = new ObjectMapper();
    }

    public String resolveOpenid(String code) {
        return resolveOpenid(code, null);
    }

    public String resolveOpenid(String code, String trustedOpenid) {
        String cloudOpenid = cloudOpenApiEnabled ? normalizeTrustedOpenid(trustedOpenid) : null;
        if (cloudOpenid != null) {
            return cloudOpenid;
        }
        if (code == null || code.isBlank()) {
            throw new BusinessException(cloudOpenApiEnabled
                    ? "未获取到微信用户标识，请通过 wx.cloud.callContainer 调用"
                    : "微信登录 code 不能为空");
        }
        if (code.startsWith("dev-")) {
            if (!devEnabled) throw new BusinessException("开发登录凭证在当前环境不可用");
            return code;
        }
        if (cloudOpenApiEnabled) {
            throw new BusinessException("未获取到微信用户标识，请通过 wx.cloud.callContainer 调用");
        }
        requireWechatSecret("无法完成微信登录");
        try {
            String query = "appid=" + encode(wechatAppId)
                    + "&secret=" + encode(wechatAppSecret)
                    + "&js_code=" + encode(code)
                    + "&grant_type=authorization_code";
            JsonNode node = sendJson(HttpRequest.newBuilder()
                    .uri(URI.create("https://api.weixin.qq.com/sns/jscode2session?" + query))
                    .timeout(REQUEST_TIMEOUT)
                    .GET().build());
            if (node.path("errcode").asInt(0) != 0) {
                throw new BusinessException("微信登录失败: " + node.path("errmsg").asText("未知错误"));
            }
            String openid = node.path("openid").asText("");
            if (openid.isBlank()) throw new BusinessException("微信登录失败: 响应缺少 openid");
            return openid;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("微信登录服务暂时不可用");
        }
    }

    public String resolvePhoneByCode(String phoneCode) {
        try {
            if (phoneCode == null || phoneCode.isBlank()) {
                throw new BusinessException("手机号凭证不能为空");
            }
            String body = mapper.writeValueAsString(Map.of("code", phoneCode));
            URI uri = CLOUD_PHONE_URI;
            if (!cloudOpenApiEnabled) {
                String token = getAccessToken();
                uri = URI.create("https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token="
                        + encode(token));
            }
            JsonNode node = sendJson(HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build());
            if (node.path("errcode").asInt(-1) != 0) {
                throw new BusinessException("获取手机号失败: " + node.path("errmsg").asText("未知错误"));
            }
            String phone = node.path("phone_info").path("phoneNumber").asText("");
            if (phone.isBlank()) throw new BusinessException("获取手机号失败: 响应缺少手机号");
            return phone;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("微信手机号服务暂时不可用");
        }
    }

    private String getAccessToken() {
        Instant now = Instant.now();
        if (cachedAccessToken != null && now.isBefore(accessTokenExpiresAt)) return cachedAccessToken;
        synchronized (this) {
            now = Instant.now();
            if (cachedAccessToken != null && now.isBefore(accessTokenExpiresAt)) return cachedAccessToken;
            requireWechatSecret("无法获取 access_token");
            String sharedToken = sharedAccessToken(now);
            if (sharedToken != null) {
                return sharedToken;
            }
            try {
                String query = "grant_type=client_credential&appid=" + encode(wechatAppId)
                        + "&secret=" + encode(wechatAppSecret);
                JsonNode node = sendJson(HttpRequest.newBuilder()
                        .uri(URI.create("https://api.weixin.qq.com/cgi-bin/token?" + query))
                        .timeout(REQUEST_TIMEOUT)
                        .GET().build());
                String token = node.path("access_token").asText("");
                if (token.isBlank()) {
                    throw new BusinessException("获取 access_token 失败: " + node.path("errmsg").asText("未知错误"));
                }
                long expiresIn = Math.max(120, node.path("expires_in").asLong(7200));
                cachedAccessToken = token;
                accessTokenExpiresAt = now.plusSeconds(expiresIn - 60);
                if (redisCache != null) {
                    redisCache.put(accessTokenCacheKey(),
                            accessTokenExpiresAt.getEpochSecond() + "\n" + token,
                            Duration.ofSeconds(expiresIn - 60));
                }
                return token;
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException("微信凭证服务暂时不可用");
            }
        }
    }

    private String sharedAccessToken(Instant now) {
        if (redisCache == null) {
            return null;
        }
        String cached = redisCache.get(accessTokenCacheKey());
        if (cached == null) {
            return null;
        }
        int separator = cached.indexOf('\n');
        if (separator <= 0 || separator == cached.length() - 1) {
            redisCache.delete(accessTokenCacheKey());
            return null;
        }
        try {
            Instant sharedExpiry = Instant.ofEpochSecond(Long.parseLong(cached.substring(0, separator)));
            if (!now.isBefore(sharedExpiry)) {
                redisCache.delete(accessTokenCacheKey());
                return null;
            }
            cachedAccessToken = cached.substring(separator + 1);
            Instant localExpiry = now.plus(localCacheTtl);
            accessTokenExpiresAt = localExpiry.isBefore(sharedExpiry) ? localExpiry : sharedExpiry;
            return cachedAccessToken;
        } catch (RuntimeException invalidCacheValue) {
            redisCache.delete(accessTokenCacheKey());
            return null;
        }
    }

    private String accessTokenCacheKey() {
        return "wechat:access-token:" + wechatAppId;
    }

    private JsonNode sendJson(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BusinessException("微信服务返回异常状态: " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private void requireWechatSecret(String action) {
        if (wechatAppSecret == null || wechatAppSecret.isBlank()) {
            throw new BusinessException("未配置 WECHAT_APP_SECRET，" + action);
        }
    }

    private String normalizeTrustedOpenid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 128 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException("微信用户标识格式不正确");
        }
        return normalized;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
