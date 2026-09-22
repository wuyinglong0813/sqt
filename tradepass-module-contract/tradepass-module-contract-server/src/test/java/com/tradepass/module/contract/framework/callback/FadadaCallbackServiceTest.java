package com.tradepass.module.contract.framework.callback;
import com.tradepass.module.contract.dal.mysql.signing.FadadaCallbackEventMapper;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaCallbackEventDO;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasc.open.api.utils.crypt.FddCryptUtil;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FadadaCallbackServiceTest {
    private FadadaCallbackEventMapper eventMapper;
    private FadadaCallbackProcessor processor;
    private FadadaCallbackService service;

    @BeforeEach
    void setUp() {
        MybatisTestSupport.initialize(FadadaCallbackEventDO.class);
        eventMapper = mock(FadadaCallbackEventMapper.class);
        processor = mock(FadadaCallbackProcessor.class);
        FadadaProperties properties = new FadadaProperties();
        properties.setEnabled(true);
        properties.setAppId("test-app-id");
        properties.setAppSecret("test-secret");
        service = new FadadaCallbackService(properties, eventMapper, processor);
    }

    @Test
    void acceptsOfficialHmacCallbackOnce() throws Exception {
        String body = "{\"signTaskId\":\"task-8\",\"signTaskStatus\":\"task_finished\"}";
        AtomicReference<FadadaCallbackEventDO> saved = new AtomicReference<>();
        when(eventMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> saved.get());
        doAnswer(invocation -> {
            FadadaCallbackEventDO event = invocation.getArgument(0);
            event.setId(28L);
            saved.set(event);
            return 1;
        }).when(eventMapper).insert(any(FadadaCallbackEventDO.class));
        HttpHeaders headers = signedHeaders(body, "sign-task-finished", "nonce-8");

        service.accept(headers, body);
        service.accept(headers, body);

        assertThat(saved.get().getEventId()).isEqualTo("sign-task-finished:nonce-8");
        assertThat(saved.get().getPayloadSha256()).hasSize(64);
        verify(processor, org.mockito.Mockito.times(2)).processAsync(28L);
        verify(eventMapper).insert(any(FadadaCallbackEventDO.class));
        assertThat(saved.get().getRetryPayload()).isEqualTo("{\"signTaskId\":\"task-8\"}");
    }

    @Test
    void failedLegacyEventCanRecoverFromProviderRedelivery() throws Exception {
        String body = "{\"signTaskId\":\"task-9\"}";
        FadadaCallbackEventDO event = new FadadaCallbackEventDO(); event.setId(29L); event.setStatus("FAILED");
        event.setPayloadSha256(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        when(eventMapper.selectOne(any(Wrapper.class))).thenReturn(event);
        service.accept(signedHeaders(body, "sign-task-finished", "nonce-9"), body);
        verify(eventMapper).restoreLegacyPayload(org.mockito.ArgumentMatchers.eq(29L),
                org.mockito.ArgumentMatchers.eq(body), any());
        verify(processor).processAsync(29L);
    }

    @Test
    void completedDuplicateDoesNotRestartProcessing() throws Exception {
        String body = "{\"signTaskId\":\"task-10\"}";
        FadadaCallbackEventDO event = new FadadaCallbackEventDO(); event.setId(30L); event.setStatus("PROCESSED");
        event.setPayloadSha256(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        when(eventMapper.selectOne(any(Wrapper.class))).thenReturn(event);
        service.accept(signedHeaders(body, "sign-task-finished", "nonce-10"), body);
        org.mockito.Mockito.verifyNoInteractions(processor);
    }

    @Test
    void rejectedAsyncDispatchLeavesARecoverableEventWithoutSensitivePayload() throws Exception {
        String body = "{\"clientUserId\":\"user-1\",\"identProcessStatus\":\"success\",\"idCard\":\"sensitive\"}";
        AtomicReference<FadadaCallbackEventDO> saved = new AtomicReference<>();
        doAnswer(inv -> { FadadaCallbackEventDO event = inv.getArgument(0); event.setId(31L); saved.set(event); return 1; })
                .when(eventMapper).insert(any(FadadaCallbackEventDO.class));
        org.mockito.Mockito.doThrow(new java.util.concurrent.RejectedExecutionException("busy")).when(processor).processAsync(31L);
        service.accept(signedHeaders(body, "user-ident", "nonce-11"), body);
        assertThat(saved.get().getStatus()).isEqualTo("RECEIVED");
        assertThat(saved.get().getNextAttemptAt()).isNotNull();
        assertThat(saved.get().getRetryPayload()).contains("clientUserId", "identProcessStatus").doesNotContain("idCard", "sensitive");
    }

    @Test
    void acknowledgesButIgnoresInvalidSignature() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-FASC-App-Id", "test-app-id");
        headers.set("X-FASC-Sign-Type", "HMAC-SHA256");
        headers.set("X-FASC-Sign", "invalid");
        headers.set("X-FASC-Timestamp", String.valueOf(Instant.now().toEpochMilli()));
        headers.set("X-FASC-Nonce", "nonce-9");
        headers.set("X-FASC-Event", "sign-task-finished");

        service.accept(headers, "{}");

        verify(eventMapper, never()).insert(any(FadadaCallbackEventDO.class));
        verify(processor, never()).processAsync(any());
    }

    private HttpHeaders signedHeaders(String body, String event, String nonce) throws Exception {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        Map<String, String> parameters = new HashMap<>();
        parameters.put("X-FASC-App-Id", "test-app-id");
        parameters.put("X-FASC-Sign-Type", "HMAC-SHA256");
        parameters.put("X-FASC-Timestamp", timestamp);
        parameters.put("X-FASC-Nonce", nonce);
        parameters.put("X-FASC-Event", event);
        parameters.put("bizContent", body);
        String signature = FddCryptUtil.sign(FddCryptUtil.sortParameters(parameters), timestamp, "test-secret");
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-FASC-App-Id", "test-app-id");
        headers.set("X-FASC-Sign-Type", "HMAC-SHA256");
        headers.set("X-FASC-Sign", signature);
        headers.set("X-FASC-Timestamp", timestamp);
        headers.set("X-FASC-Nonce", nonce);
        headers.set("X-FASC-Event", event);
        return headers;
    }
}
