package com.tradepass.framework.fadada.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Evidence from an authenticated callback, never from a browser redirect. */
public final class FadadaAuthorizationCallback {
    private FadadaAuthorizationCallback() {}

    public static boolean completed(JsonNode data, String event, String processField, List<String> requiredScopes) {
        return event.equals(text(data, "_verifiedEvent"))
                && "success".equals(text(data, "authResult"))
                && "success".equals(text(data, processField))
                && "enable".equals(text(data, "availableStatus"))
                && time(data) != null && scopes(data).containsAll(requiredScopes);
    }

    public static String text(JsonNode data, String field) {
        JsonNode value = data == null ? null : data.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    public static List<String> scopes(JsonNode data) {
        JsonNode values = data == null ? null : data.get("authScope");
        if (values == null || !values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) return List.of();
            result.add(value.asText());
        }
        return result;
    }

    public static LocalDateTime time(JsonNode data) {
        try {
            Instant instant = Instant.ofEpochMilli(Long.parseLong(text(data, "eventTime")));
            if (instant.isBefore(Instant.EPOCH) || instant.isAfter(Instant.now().plusSeconds(300))) return null;
            return LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Shanghai"));
        } catch (RuntimeException ignored) { return null; }
    }
}
