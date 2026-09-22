package com.tradepass.framework.cache.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedBinaryCacheTest {

    @Test
    void reusesDataUntilExpiryAndEvictsLeastRecentlyUsedEntriesBySize() {
        AtomicLong now = new AtomicLong(1_000);
        AtomicInteger loads = new AtomicInteger();
        BoundedBinaryCache cache = new BoundedBinaryCache(
                Duration.ofSeconds(10), 6, now::get);

        assertThat(cache.get("a", () -> loaded(loads, "aaa"))).asString().isEqualTo("aaa");
        assertThat(cache.get("a", () -> loaded(loads, "xxx"))).asString().isEqualTo("aaa");
        assertThat(loads).hasValue(1);

        cache.get("b", () -> loaded(loads, "bbb"));
        cache.get("a", () -> loaded(loads, "xxx"));
        cache.get("c", () -> loaded(loads, "ccc"));
        assertThat(cache.getIfPresent("b")).isNull();
        assertThat(cache.getIfPresent("a")).asString().isEqualTo("aaa");

        now.addAndGet(10_001);
        assertThat(cache.getIfPresent("a")).isNull();
    }

    private byte[] loaded(AtomicInteger loads, String value) {
        loads.incrementAndGet();
        return value.getBytes();
    }
}
