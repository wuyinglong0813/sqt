package com.tradepass.framework.fadada.core;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyFadadaCompanyIdentityClientTest {
    private static final String SIGNATURE = "cc16afa23ec4e69ea7db592cac93869fb148203f61eb8ee3eff20463fbc736a0";
    private static final String SUCCESS = """
            {"code":"100000","data":{"openCorpId":"corp-3","corpIdentStatus":"identified",
             "corpIdentInfo":{"corpName":"测试企业","corpIdentNo":"TEST-CREDIT","legalRepName":"测试法人"},
             "corpIdentMethod":"legal_rep","identSubmitTime":"1699999900000","identSuccessTime":"1700000000000",
             "operatorType":"legal_rep","operatorId":"open-user-7"}}
            """;

    @Test
    void legacyRequestSignsTheActualHeadersAndJsonWithoutAnySubVersion() {
        List<LegacyFadadaCompanyIdentityClient.Request> requests = new ArrayList<>();
        var client = client(tokens(), request -> {
            requests.add(request);
            return reply(SUCCESS);
        });

        var result = client.getIdentity("corp-3");

        var request = requests.get(0);
        assertThat(request.endpoint().toString()).isEqualTo("https://example.test/api/v5/corp/get-identity-info");
        assertThat(request.headers()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "X-FASC-App-Id", "test-app", "X-FASC-Sign-Type", "HMAC-SHA256", "X-FASC-AccessToken", "test-token",
                "X-FASC-Timestamp", "1700000000000", "X-FASC-Nonce", "0123456789abcdef0123456789abcdef",
                "X-FASC-Sign", SIGNATURE));
        // This known signature was independently generated with Python hashlib/hmac.
        assertThat(request.headers()).doesNotContainKeys("X-FASC-Api-SubVersion", "X-FASC-Grant-Type");
        assertThat(URLDecoder.decode(request.form(), StandardCharsets.UTF_8))
                .isEqualTo("bizContent={\"openCorpId\":\"corp-3\"}").doesNotContain("test-token");
        assertThat(result.operatorOpenUserIdDocumented()).isTrue();
        assertThat(result.operatorId()).isEqualTo("open-user-7");
        assertThat(result.companyName()).isEqualTo("测试企业");
        assertThat(request.toString()).doesNotContain("test-token", "test-secret", "corp-3");
        assertThat(reply(SUCCESS).toString()).doesNotContain("测试法人", "open-user-7");
    }

    @Test
    void onlyAnExplicitExpiredTokenRefreshesAndRetriesOnce() {
        var tokens = tokens();
        when(tokens.get()).thenReturn("old-token", "new-token");
        List<LegacyFadadaCompanyIdentityClient.Request> requests = new ArrayList<>();
        var client = client(tokens, request -> {
            requests.add(request);
            return reply(requests.size() == 1 ? "{\"code\":\"100002\"}" : SUCCESS);
        });

        assertThat(client.getIdentity("corp-3").operatorOpenUserIdDocumented()).isTrue();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).headers()).containsEntry("X-FASC-AccessToken", "old-token");
        assertThat(requests.get(1).headers()).containsEntry("X-FASC-AccessToken", "new-token");
        assertThat(requests.get(1).headers().get("X-FASC-Sign")).isNotEqualTo(requests.get(0).headers().get("X-FASC-Sign"));
        verify(tokens).invalidate("old-token");
        verify(tokens, times(2)).get();
    }

    @Test
    void repeatedExpiryStopsAfterTheSecondAttempt() {
        var tokens = tokens();
        AtomicInteger calls = new AtomicInteger();
        var client = client(tokens, request -> {
            calls.incrementAndGet();
            return reply("{\"code\":\"100002\",\"msg\":\"private response\"}");
        });

        assertThatThrownBy(() -> client.getIdentity("corp-3")).isInstanceOf(BusinessException.class)
                .hasMessage("企业历史实名信息暂不可用，请稍后重试").hasNoCause();
        assertThat(calls).hasValue(2);
        verify(tokens).invalidate("test-token");
        verify(tokens, times(2)).get();
    }

    @Test
    void nonSuccessHttpResponsesNeverRefreshTokensOrExposeTheirBodies() {
        for (int status : List.of(301, 302, 307, 400, 500)) {
            var tokens = tokens();
            AtomicInteger calls = new AtomicInteger();
            var client = client(tokens, request -> {
                calls.incrementAndGet();
                return new LegacyFadadaCompanyIdentityClient.Reply(status, "{\"code\":\"100002\",\"msg\":\"private identity\"}");
            });
            assertThatThrownBy(() -> client.getIdentity("corp-3")).hasMessage("企业历史实名信息暂不可用，请稍后重试").hasNoCause();
            assertThat(calls).hasValue(1);
            verify(tokens, never()).invalidate(any());
        }
    }

    @Test
    void missingMalformedAndNonStringIdentityEvidenceNeverBecomesDocumentedAuthority() {
        for (String body : List.of("not json: private response", "null", "[]", "{\"code\":\"100000\"}",
                SUCCESS.replace("\"open-user-7\"", "7"), SUCCESS.replace("\"corp-3\"", "3"),
                SUCCESS.replace("\"operatorId\":\"open-user-7\"", "\"operatorId\":null"),
                SUCCESS.replace("\"legal_rep\",\"operatorId\"", "7,\"operatorId\""),
                SUCCESS.replace("\"corp-3\"", "\"different-corp\""),
                SUCCESS.replace("\"identified\"", "\"unidentified\""))) {
            var client = client(tokens(), request -> reply(body));
            assertThatThrownBy(() -> client.getIdentity("corp-3")).hasMessage("企业历史实名信息暂不可用，请稍后重试").hasNoCause();
        }
    }

    @Test
    void transportErrorsAndProviderMessagesDoNotLeakIntoLogsOrExceptions() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LegacyFadadaCompanyIdentityClient.class);
        var messages = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        messages.start();
        logger.addAppender(messages);
        try {
            var client = client(tokens(), request -> reply("{\"code\":\"private-token\",\"msg\":\"private-name\",\"data\":null}"));
            assertThatThrownBy(() -> client.getIdentity("corp-3")).hasNoCause().hasMessageNotContaining("private");
            var unavailable = client(tokens(), request -> { throw new IOException("private-name private-token"); });
            assertThatThrownBy(() -> unavailable.getIdentity("corp-3")).hasNoCause().hasMessageNotContaining("private");
            assertThat(messages.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("private-name", "private-token", "test-secret");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(messages); }
    }

    @Test
    void actualHttpTransportKeepsSignedHeadersAndEnforcesTimeoutsAndNoRedirects() throws Exception {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        when(connection.getOutputStream()).thenReturn(sent);
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(SUCCESS.getBytes(StandardCharsets.UTF_8)));
        var transport = new LegacyFadadaCompanyIdentityClient.JdkTransport(endpoint -> connection);

        assertThat(client(tokens(), transport).getIdentity("corp-3").operatorOpenUserIdDocumented()).isTrue();

        verify(connection).setInstanceFollowRedirects(false);
        verify(connection).setConnectTimeout(10_000);
        verify(connection, atLeastOnce()).setReadTimeout(20_000);
        verify(connection).setRequestMethod("POST");
        verify(connection).setRequestProperty("X-FASC-Sign", SIGNATURE);
        verify(connection, never()).setRequestProperty(eq("X-FASC-Api-SubVersion"), anyString());
        assertThat(URLDecoder.decode(sent.toString(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .isEqualTo("bizContent={\"openCorpId\":\"corp-3\"}");
        verify(connection).disconnect();
    }

    @Test
    void redirectResponsesAreNotFollowedOrRead() throws Exception {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(connection.getResponseCode()).thenReturn(302);
        var transport = new LegacyFadadaCompanyIdentityClient.JdkTransport(endpoint -> connection);

        assertThatThrownBy(() -> client(tokens(), transport).getIdentity("corp-3")).hasNoCause();

        verify(connection).setInstanceFollowRedirects(false);
        verify(connection, never()).getInputStream();
        verify(connection, never()).getErrorStream();
        verify(connection).disconnect();
    }

    @Test
    void responsesAreBoundedBySizeAndAnOverallDeadline() throws Exception {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(
                new byte[LegacyFadadaCompanyIdentityClient.MAX_RESPONSE_BYTES + 1]));
        var limited = new LegacyFadadaCompanyIdentityClient.JdkTransport(endpoint -> connection);
        assertThatThrownBy(() -> client(tokens(), limited).getIdentity("corp-3")).hasNoCause();

        AtomicLong clock = new AtomicLong();
        var timed = new LegacyFadadaCompanyIdentityClient.JdkTransport(endpoint -> connection,
                () -> clock.getAndAdd(Duration.ofSeconds(21).toNanos()));
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(SUCCESS.getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> client(tokens(), timed).getIdentity("corp-3")).hasNoCause();
        verify(connection, times(2)).disconnect();
    }

    @Test
    void interruptionIsPreservedAndPrivateErrorDetailsAreDiscarded() {
        try {
            var client = client(tokens(), request -> { throw new InterruptedException("private identity"); });
            assertThatThrownBy(() -> client.getIdentity("corp-3")).hasNoCause().hasMessageNotContaining("private");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    private FadadaAccessTokenProvider tokens() {
        var tokens = mock(FadadaAccessTokenProvider.class);
        when(tokens.get()).thenReturn("test-token");
        return tokens;
    }

    private LegacyFadadaCompanyIdentityClient client(FadadaAccessTokenProvider tokens,
                                                     LegacyFadadaCompanyIdentityClient.Transport transport) {
        return new LegacyFadadaCompanyIdentityClient(properties(), tokens, transport,
                () -> 1700000000000L, () -> "0123456789abcdef0123456789abcdef");
    }

    static FadadaProperties properties() {
        var properties = new FadadaProperties();
        properties.setAppId("test-app"); properties.setAppSecret("test-secret");
        properties.setServerUrl("https://example.test/api/v5");
        return properties;
    }

    private LegacyFadadaCompanyIdentityClient.Reply reply(String body) {
        return new LegacyFadadaCompanyIdentityClient.Reply(200, body);
    }
}
