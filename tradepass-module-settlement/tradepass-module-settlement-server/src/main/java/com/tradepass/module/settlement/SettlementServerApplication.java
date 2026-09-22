package com.tradepass.module.settlement;

import com.tradepass.framework.runtime.core.ServiceLauncher;
import com.tradepass.module.settlement.framework.config.SettlementConfiguration;

/**
 * 结算对账服务启动类。
 */
public final class SettlementServerApplication {
    public static void main(String[] args) {
        ServiceLauncher.run("settlement", 1114, SettlementConfiguration.class, args);
    }
}
