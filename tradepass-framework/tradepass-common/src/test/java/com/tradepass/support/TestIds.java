package com.tradepass.support;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic IDs for JDBC mocks; restored after each test. */
public final class TestIds {
    private TestIds() { }

    public static void use(long... values) {
        AtomicInteger index = new AtomicInteger();
        IdWorker.setIdentifierGenerator(entity -> values[Math.min(index.getAndIncrement(), values.length - 1)]);
    }

    public static void reset() {
        IdWorker.setIdentifierGenerator(DefaultIdentifierGenerator.getInstance());
    }
}
