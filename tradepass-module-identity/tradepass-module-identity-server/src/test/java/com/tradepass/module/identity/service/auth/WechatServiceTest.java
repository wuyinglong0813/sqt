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
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    @SuppressWarnings("unchecked")
    void generatesMiniProgramCodeWithOnlyTheInvitationScene() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(png);
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        WechatServiceImpl service = new WechatServiceImpl("app", "", false, true, null, Duration.ofMinutes(5), client);
        assertThat(service.createInvitationCode("ABCD2345")).isEqualTo(png);
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any());
        assertThat(request.getValue().uri().getPath()).isEqualTo("/wxa/getwxacodeunlimit");
        JsonNode body = requestBody(request.getValue());
        assertThat(body.path("page").asText()).isEqualTo("pages/invitation/invitation");
        assertThat(body.path("scene").asText()).isEqualTo("ABCD2345");
        assertThat(body.path("check_path").asBoolean()).isTrue();
        assertThat(body.path("env_version").asText()).isEqualTo("release");
    }

    @Test
    @SuppressWarnings("unchecked")
    void treatsWechatJsonErrorsAsErrorsInsteadOfQrImages() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"errcode\":41030,\"errmsg\":\"invalid page\"}".getBytes());
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        WechatService service = new WechatServiceImpl("app", "", false, true, null, Duration.ofMinutes(5), client);
        assertThatThrownBy(() -> service.createInvitationCode("ABCD2345"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("invalid page");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatesHttpsLinkWithTheSameCodeAndInvitationDeadline() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"errcode\":0,\"url_link\":\"https://wxaurl.cn/example\"}");
        doReturn(response).when(client).send(any(HttpRequest.class), any());
        WechatService service = new WechatServiceImpl("app", "", false, true, null, Duration.ofMinutes(5), client);
        long deadline = Instant.now().plusSeconds(3600).getEpochSecond();
        assertThat(service.createInvitationLink("ABCD2345", deadline)).isEqualTo("https://wxaurl.cn/example");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any());
        JsonNode body = requestBody(request.getValue());
        assertThat(body.path("query").asText()).isEqualTo("inviteCode=ABCD2345&type=member");
        assertThat(body.path("expire_time").asLong()).isEqualTo(deadline);
        assertThat(body.path("expire_type").asInt()).isZero();
    }

    @Test
    void refusesInvalidOrExpiredInvitationBeforeCallingWechat() {
        HttpClient client = mock(HttpClient.class);
        WechatService service = new WechatServiceImpl("app", "", false, true, null, Duration.ofMinutes(5), client);
        assertThatThrownBy(() -> service.createInvitationCode("../escape")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.createInvitationLink("ABCD2345", 1)).isInstanceOf(BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    private JsonNode requestBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<Void> done = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk); }
            public void onError(Throwable error) { done.completeExceptionally(error); }
            public void onComplete() { done.complete(null); }
        });
        done.join();
        return new ObjectMapper().readTree(bytes.toByteArray());
    }

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
