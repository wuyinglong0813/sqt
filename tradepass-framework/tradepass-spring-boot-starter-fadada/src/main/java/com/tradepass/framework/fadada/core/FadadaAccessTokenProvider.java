package com.tradepass.framework.fadada.core;

import com.fasc.open.api.bean.base.BaseRes;
import com.fasc.open.api.exception.ApiException;
import com.fasc.open.api.v5_1.client.OpenApiClient;
import com.fasc.open.api.v5_1.client.ServiceClient;
import com.fasc.open.api.v5_1.res.service.AccessTokenRes;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class FadadaAccessTokenProvider {
    private static final Logger log = LoggerFactory.getLogger(FadadaAccessTokenProvider.class);
    private final ServiceClient serviceClient;
    private volatile String accessToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public FadadaAccessTokenProvider(FadadaProperties properties) {
        this.serviceClient = new ServiceClient(new OpenApiClient(
                properties.getAppId(), properties.getAppSecret(), properties.getServerUrl()));
    }

    public String get() {
        Instant now = Instant.now();
        if (hasText(accessToken) && now.isBefore(expiresAt)) return accessToken;
        synchronized (this) {
            now = Instant.now();
            if (hasText(accessToken) && now.isBefore(expiresAt)) return accessToken;
            try {
                BaseRes<AccessTokenRes> response = serviceClient.getAccessToken();
                if (response == null || !response.isSuccess() || response.getData() == null
                        || !hasText(response.getData().getAccessToken())) {
                    log.warn("Electronic signature access token failed: code={}, requestId={}",
                            response == null ? "null" : response.getCode(),
                            response == null ? "" : response.getRequestId());
                    throw new BusinessException("电子签服务暂不可用，请稍后重试");
                }
                AccessTokenRes value = response.getData();
                long expiresIn = parse(value.getExpiresIn());
                accessToken = value.getAccessToken();
                expiresAt = now.plusSeconds(Math.max(60, expiresIn - 60));
                return accessToken;
            } catch (ApiException exception) {
                log.warn("Electronic signature access token request failed", exception);
                throw new BusinessException("电子签服务暂不可用，请稍后重试");
            }
        }
    }

    private long parse(String value) {
        try { return Math.max(120, Long.parseLong(value)); }
        catch (NumberFormatException ignored) { return 7200; }
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
