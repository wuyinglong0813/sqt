package com.tradepass.module.identity.service.auth;

import com.tradepass.framework.cache.core.RedisCacheService;

import com.tradepass.framework.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class WechatServiceTest {

    @Test
    void acceptsDevelopmentCredentialOnlyInDevelopment() {
        assertThat(new WechatServiceImpl("app", "", true).resolveOpenid("dev-user-1"))
                .isEqualTo("dev-user-1");

        assertThatThrownBy(() -> new WechatServiceImpl("app", "", false).resolveOpenid("dev-user-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("开发登录凭证在当前环境不可用");
    }

    @Test
    void rejectsWechatCallsWhenSecretIsMissing() {
        WechatService service = new WechatServiceImpl("app", "", false);

        assertThatThrownBy(() -> service.resolveOpenid("real-code"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未配置 WECHAT_APP_SECRET，无法完成微信登录");
        assertThatThrownBy(() -> service.resolvePhoneByCode("phone-code"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未配置 WECHAT_APP_SECRET，无法获取 access_token");
    }

    @Test
    void trustsCallContainerOpenidWithoutWechatSecret() {
        WechatService service = new WechatServiceImpl("app", "", false, true, null,
                Duration.ofMinutes(5), mock(HttpClient.class));

        assertThat(service.resolveOpenid(null, "cloud-openid")).isEqualTo("cloud-openid");
        assertThatThrownBy(() -> service.resolveOpenid("wx-login-code", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未获取到微信用户标识，请通过 wx.cloud.callContainer 调用");
    }

    @Test
    void ignoresCallerSuppliedOpenidOutsideCloudMode() {
        WechatService service = new WechatServiceImpl("app", "", false);

        assertThatThrownBy(() -> service.resolveOpenid(null, "spoofed-openid"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("微信登录 code 不能为空");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getsPhoneThroughCloudOpenApiWithoutAccessToken() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"errcode":0,"errmsg":"ok","phone_info":{"phoneNumber":"13800000000"}}
                """);
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());
        WechatService service = new WechatServiceImpl("app", "", false, true, null,
                Duration.ofMinutes(5), httpClient);

        assertThat(service.resolvePhoneByCode("phone-code")).isEqualTo("13800000000");

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(), any());
        assertThat(request.getValue().uri()).isEqualTo(
                URI.create("http://api.weixin.qq.com/wxa/business/getuserphonenumber"));
        assertThat(request.getValue().uri().getQuery()).isNull();
    }

    @Test
    void reusesSharedAccessTokenFromRedis() {
        RedisCacheService redisCache = mock(RedisCacheService.class);
        long expiresAt = Instant.now().plusSeconds(600).getEpochSecond();
        when(redisCache.get("wechat:access-token:app"))
                .thenReturn(expiresAt + "\nshared-token");
        WechatService service = new WechatServiceImpl(
                "app", "secret", false, redisCache, Duration.ofMinutes(5));

        String token = ReflectionTestUtils.invokeMethod(service, "getAccessToken");

        assertThat(token).isEqualTo("shared-token");
    }
}
