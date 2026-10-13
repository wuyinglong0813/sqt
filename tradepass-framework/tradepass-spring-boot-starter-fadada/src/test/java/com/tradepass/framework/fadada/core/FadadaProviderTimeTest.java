package com.tradepass.framework.fadada.core;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class FadadaProviderTimeTest {
    @Test void convertsDocumentedUnixMillisecondsToBeijingTime() {
        var value = Instant.parse("2026-10-13T08:14:46Z");
        assertThat(FadadaProviderTime.parse(String.valueOf(value.toEpochMilli()), null))
                .isEqualTo(LocalDateTime.of(2026, 10, 13, 16, 14, 46));
    }
    @Test void preservesOffsetAndLegacyLocalFormats() {
        var expected = LocalDateTime.of(2026, 10, 13, 16, 14, 46);
        for (var value : java.util.List.of("2026-10-13T08:14:46Z", "2026-10-13T16:14:46+08:00",
                "2026-10-13 16:14:46", "20261013161446")) {
            assertThat(FadadaProviderTime.parse(value, null)).isEqualTo(expected);
        }
    }
    @Test void missingOrInvalidOptionalTimeUsesTheExplicitFallback() {
        var fallback = LocalDateTime.of(2026, 10, 13, 16, 30);
        assertThat(FadadaProviderTime.parse(null, fallback)).isEqualTo(fallback);
        assertThat(FadadaProviderTime.parse("invalid", fallback)).isEqualTo(fallback);
    }
}
