package com.tradepass.module.file.framework.file.core.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.storage.config.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

class CloudBaseOpenApiClient {
    static final URI GET_AUTH_URI = URI.create("http://api.weixin.qq.com/_/cos/getauth");
    static final URI ENCODE_META_URI = URI.create("http://api.weixin.qq.com/_/cos/metaid/encode");

    private static final Logger log = LoggerFactory.getLogger(CloudBaseOpenApiClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    CloudBaseOpenApiClient(ObjectMapper objectMapper, StorageProperties properties) {
        this(objectMapper, HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(Math.max(1000, properties.getConnectionTimeoutMillis())))
                        .build(),
                Duration.ofMillis(Math.max(1000, properties.getSocketTimeoutMillis())));
    }

    CloudBaseOpenApiClient(ObjectMapper objectMapper, HttpClient httpClient, Duration requestTimeout) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
    }

    TemporaryCredential getTemporaryCredential() {
        JsonNode payload = execute(HttpRequest.newBuilder(GET_AUTH_URI)
                .timeout(requestTimeout)
                .GET()
                .build(), "获取临时凭证");
        rejectOpenApiError(payload, "获取临时凭证");
        String secretId = requiredText(payload, "TmpSecretId");
        String secretKey = requiredText(payload, "TmpSecretKey");
        String token = requiredText(payload, "Token");
        long expiredTime = payload.path("ExpiredTime").asLong(0);
        if (expiredTime <= 0) {
            throw invalidResponse("获取临时凭证");
        }
        return new TemporaryCredential(secretId, secretKey, token, expiredTime);
    }

    String encodeFileMetadata(String bucket, String objectKey) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(Map.of(
                    "openid", "",
                    "bucket", bucket,
                    "paths", new String[]{objectKey}
            ));
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成云托管对象元数据请求", exception);
        }
        JsonNode payload = execute(HttpRequest.newBuilder(ENCODE_META_URI)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(), "生成文件元数据");
        rejectOpenApiError(payload, "生成文件元数据");
        String metaId = payload.path("respdata").path("x_cos_meta_field_strs").path(0).asText("");
        if (metaId.isBlank()) {
            throw invalidResponse("生成文件元数据");
        }
        return metaId;
    }

    private JsonNode execute(HttpRequest request, String action) {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("微信云托管对象存储{}失败，status={}, seqid={}", action,
                        response.statusCode(), response.headers().firstValue("x-openapi-seqid").orElse(""));
                throw new BusinessException("微信云托管对象存储授权失败，请稍后重试");
            }
            JsonNode payload = objectMapper.readTree(response.body());
            if (payload == null || !payload.isObject()) {
                throw invalidResponse(action);
            }
            return payload;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("微信云托管对象存储授权失败，请稍后重试");
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            log.error("微信云托管对象存储{}调用失败", action, exception);
            throw new BusinessException("微信云托管对象存储授权失败，请稍后重试");
        }
    }

    private void rejectOpenApiError(JsonNode payload, String action) {
        if (payload.has("errcode") && payload.path("errcode").asInt(-1) != 0) {
            log.error("微信云托管对象存储{}失败，errcode={}, errmsg={}", action,
                    payload.path("errcode").asInt(), payload.path("errmsg").asText(""));
            throw new BusinessException("微信云托管对象存储授权失败，请稍后重试");
        }
    }

    private String requiredText(JsonNode payload, String field) {
        String value = payload.path(field).asText("");
        if (value.isBlank()) {
            throw invalidResponse("获取临时凭证");
        }
        return value;
    }

    private BusinessException invalidResponse(String action) {
        log.error("微信云托管对象存储{}返回数据不完整", action);
        return new BusinessException("微信云托管对象存储授权失败，请稍后重试");
    }

    record TemporaryCredential(String secretId, String secretKey, String token, long expiredTime) {
    }
}
