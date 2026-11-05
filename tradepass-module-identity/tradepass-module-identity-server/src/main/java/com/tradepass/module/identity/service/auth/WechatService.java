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

public interface WechatService {
    String resolveOpenid(String code);
    String resolveOpenid(String code, String trustedOpenid);
    String resolvePhoneByCode(String phoneCode);
    default byte[] createInvitationCode(String code) {
        throw new BusinessException("微信小程序码服务暂不可用");
    }
    default String createInvitationLink(String code, long expiresAt) {
        throw new BusinessException("微信邀请链接服务暂不可用");
    }
}
