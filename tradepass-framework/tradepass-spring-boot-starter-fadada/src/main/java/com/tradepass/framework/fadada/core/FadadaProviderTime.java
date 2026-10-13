package com.tradepass.framework.fadada.core;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class FadadaProviderTime {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private FadadaProviderTime() {}

    public static LocalDateTime parse(String value, LocalDateTime fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            if (value.matches("[0-9]{1,13}")) {
                return Instant.ofEpochMilli(Long.parseLong(value)).atZone(ZONE).toLocalDateTime();
            }
            return OffsetDateTime.parse(value).atZoneSameInstant(ZONE).toLocalDateTime();
        } catch (RuntimeException ignored) { }
        for (var format : List.of(DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))) {
            try { return LocalDateTime.parse(value, format); }
            catch (RuntimeException ignored) { }
        }
        return fallback;
    }
}
