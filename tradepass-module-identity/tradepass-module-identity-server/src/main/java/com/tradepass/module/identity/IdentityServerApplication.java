package com.tradepass.module.identity;

import com.tradepass.framework.runtime.core.ServiceLauncher;
import com.tradepass.module.identity.framework.config.IdentityConfiguration;

/**
 * 身份认证服务启动类。
 */
public final class IdentityServerApplication {
    public static void main(String[] args) {
        ServiceLauncher.run("identity", 1111, IdentityConfiguration.class, args);
    }
}
