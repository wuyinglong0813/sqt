package com.tradepass.module.contract.framework.callback;
import com.tradepass.module.contract.dal.mysql.signing.FadadaCallbackEventMapper;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaCallbackEventDO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasc.open.api.utils.crypt.FddCryptUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@Service
public class FadadaCallbackService {
    private static final Logger log = LoggerFactory.getLogger(FadadaCallbackService.class);
    private static final long MAX_CLOCK_SKEW_MILLIS = 300_000L;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FadadaProperties properties;
    private final FadadaCallbackEventMapper eventMapper;
    private final FadadaCallbackProcessor processor;
    private CallbackDispatcher dispatcher;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDispatcher(CallbackDispatcher dispatcher) { this.dispatcher = dispatcher; }

    public FadadaCallbackService(FadadaProperties properties, FadadaCallbackEventMapper eventMapper,
                                 FadadaCallbackProcessor processor) {
        this.properties = properties;
        this.eventMapper = eventMapper;
        this.processor = processor;
    }

    public void accept(HttpHeaders headers, String bizContent) {
        String appId = headers.getFirst("X-FASC-App-Id");
        String signType = headers.getFirst("X-FASC-Sign-Type");
        String sign = headers.getFirst("X-FASC-Sign");
        String timestamp = headers.getFirst("X-FASC-Timestamp");
        String nonce = headers.getFirst("X-FASC-Nonce");
        String eventType = headers.getFirst("X-FASC-Event");
        if (!valid(appId, signType, sign, timestamp, nonce, eventType, bizContent)) {
            log.warn("Ignored invalid electronic signature callback: event={}", safe(eventType));
            return;
        }
        String eventId = safe(eventType) + ":" + safe(nonce);
        FadadaCallbackEventDO event = eventMapper.selectOne(new LambdaQueryWrapper<FadadaCallbackEventDO>()
                .eq(FadadaCallbackEventDO::getEventId, eventId).last("LIMIT 1"));
        if (event != null) {
            retryExisting(event, bizContent);
            return;
        }
        event = new FadadaCallbackEventDO();
        event.setEventId(eventId);
        event.setEventType(eventType);
        event.setSubjectType("CALLBACK");
        event.setPayloadSha256(sha256(bizContent));
        event.setRetryPayload(retryPayload(bizContent));
        event.setAttemptCount(0);
        event.setNextAttemptAt(LocalDateTime.now());
        event.setStatus("RECEIVED");
        event.setReceivedAt(LocalDateTime.now());
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException duplicate) {
            FadadaCallbackEventDO existing = eventMapper.selectOne(new LambdaQueryWrapper<FadadaCallbackEventDO>()
                    .eq(FadadaCallbackEventDO::getEventId, eventId).last("LIMIT 1"));
            if (existing != null) retryExisting(existing, bizContent);
            return;
        }
        dispatch(event.getId());
    }

    private void retryExisting(FadadaCallbackEventDO event, String body) {
        if (!sha256(body).equals(event.getPayloadSha256())) {
            log.warn("Ignored callback with conflicting payload: eventId={}", event.getId());
            return;
        }
        if ("PROCESSED".equals(event.getStatus()) || "IGNORED".equals(event.getStatus())) return;
        if (event.getRetryPayload() == null) {
            eventMapper.restoreLegacyPayload(event.getId(), retryPayload(body), LocalDateTime.now());
        }
        dispatch(event.getId());
    }

    private void dispatch(Long id) {
        try {
            if (dispatcher != null) dispatcher.dispatch(id);
            else processor.processAsync(id);
        }
        catch (RuntimeException exception) {
            // The event is already durable. The scheduled worker will recover it.
            log.warn("Callback dispatch deferred to recovery worker: eventId={}", id, exception);
        }
    }

    private String retryPayload(String body) {
        try {
            var source = objectMapper.readTree(body);
            if (source == null || !source.isObject()) throw new IllegalArgumentException("Expected callback object");
            var result = objectMapper.createObjectNode();
            // Persist only routing identifiers and status details used by the processor,
            // never arbitrary provider fields such as full identity documents or face data.
            for (String field : java.util.List.of("clientUserId", "clientCorpId", "openCorpId", "signTaskId",
                    "openUserId", "existOpenUserId", "authResult", "verifyStatus", "corpIdentProcessStatus",
                    "corpIdentFailedReason", "authFailedReason", "identProcessStatus", "identMethod", "identFailedReason")) {
                var value = source.get(field);
                if (value != null && value.isValueNode() && !value.isNull()) {
                    String text = value.asText();
                    if (text.length() > 512) text = text.substring(0, 512);
                    result.put(field, text);
                }
            }
            return result.toString();
        } catch (Exception exception) {
            throw new BusinessException("回调数据格式不正确");
        }
    }

    private boolean valid(String appId, String signType, String sign, String timestamp,
                          String nonce, String event, String bizContent) {
        if (!properties.isEnabled() || !hasText(properties.getAppId()) || !hasText(properties.getAppSecret())) return false;
        if (!properties.getAppId().equals(appId) || !"HMAC-SHA256".equals(signType)
                || !hasText(sign) || !hasText(nonce) || nonce.length() > 32 || !hasText(event)
                || bizContent == null) return false;
        try {
            long eventTime = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().toEpochMilli() - eventTime) > MAX_CLOCK_SKEW_MILLIS) return false;
            Map<String, String> parameters = new HashMap<>();
            parameters.put("X-FASC-App-Id", appId);
            parameters.put("X-FASC-Sign-Type", signType);
            parameters.put("X-FASC-Timestamp", timestamp);
            parameters.put("X-FASC-Nonce", nonce);
            parameters.put("X-FASC-Event", event);
            parameters.put("bizContent", bizContent);
            String expected = FddCryptUtil.sign(FddCryptUtil.sortParameters(parameters), timestamp,
                    properties.getAppSecret());
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    sign.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            return false;
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Callback digest failed", exception);
        }
    }
    private String safe(String value) { return value == null ? "" : value; }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
