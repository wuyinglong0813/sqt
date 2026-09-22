package com.tradepass.module.contract;

import com.tradepass.framework.runtime.core.ServiceLauncher;
import com.tradepass.module.contract.framework.config.ContractConfiguration;

/**
 * 合同签署服务启动类。
 */
public final class ContractServerApplication {
    public static void main(String[] args) {
        ServiceLauncher.run("contract", 1112, ContractConfiguration.class, args);
    }
}
