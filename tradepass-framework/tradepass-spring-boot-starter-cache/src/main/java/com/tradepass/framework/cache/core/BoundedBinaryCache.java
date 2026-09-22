package com.tradepass.framework.cache.core;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class BoundedBinaryCache {
    private final long ttlMillis;
    private final long maxBytes;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final ConcurrentMap<String, Object> loadLocks = new ConcurrentHashMap<>();
    private long cachedBytes;

    public BoundedBinaryCache(Duration ttl, long maxBytes) {
        this(ttl, maxBytes, System::currentTimeMillis);
    }

    BoundedBinaryCache(Duration ttl, long maxBytes, LongSupplier clock) {
        this.ttlMillis = Math.max(1, ttl == null ? 1 : ttl.toMillis());
        this.maxBytes = Math.max(1, maxBytes);
        this.clock = clock;
    }

    public byte[] get(String key, Supplier<byte[]> loader) {
        byte[] cached = getIfPresent(key);
        if (cached != null) return cached;
        Object lock = loadLocks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                cached = getIfPresent(key);
                if (cached != null) return cached;
                byte[] loaded = loader.get();
                if (loaded != null && loaded.length > 0 && loaded.length <= maxBytes) {
                    put(key, loaded);
                }
                return loaded;
            }
        } finally {
            loadLocks.remove(key, lock);
        }
    }

    synchronized byte[] getIfPresent(String key) {
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expiresAt() <= clock.getAsLong()) {
            remove(key, entry);
            return null;
        }
        return entry.data();
    }

    private synchronized void put(String key, byte[] data) {
        Entry previous = entries.remove(key);
        if (previous != null) cachedBytes -= previous.data().length;
        entries.put(key, new Entry(data, clock.getAsLong() + ttlMillis));
        cachedBytes += data.length;
        evictExpired();
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (cachedBytes > maxBytes && iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            cachedBytes -= entry.data().length;
            iterator.remove();
        }
    }

    private void evictExpired() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.expiresAt() > now) continue;
            cachedBytes -= entry.data().length;
            iterator.remove();
        }
    }

    private void remove(String key, Entry entry) {
        if (entries.remove(key, entry)) cachedBytes -= entry.data().length;
    }

    private record Entry(byte[] data, long expiresAt) {
    }
}
