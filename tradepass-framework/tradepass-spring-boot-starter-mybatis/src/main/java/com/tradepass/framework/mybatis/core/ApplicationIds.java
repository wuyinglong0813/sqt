package com.tradepass.framework.mybatis.core;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;

/** Application-generated Snowflake IDs shared by mapper and JDBC writes. */
public final class ApplicationIds {
    private ApplicationIds() { }

    public static long next() {
        return IdWorker.getId();
    }
}
