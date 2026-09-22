package com.tradepass.module.trade;

import com.tradepass.framework.runtime.core.ServiceLauncher;
import com.tradepass.module.trade.framework.config.TradeConfiguration;

/**
 * 交易履约服务启动类。
 */
public final class TradeServerApplication {
    public static void main(String[] args) {
        ServiceLauncher.run("trade", 1113, TradeConfiguration.class, args);
    }
}
