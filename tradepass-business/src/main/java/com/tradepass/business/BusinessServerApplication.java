package com.tradepass.business;

import com.tradepass.framework.runtime.core.ServiceLauncher;

/** Contract, trade, settlement, and file in one process. Identity and the gateway stay separate. */
public final class BusinessServerApplication {
    public static void main(String[] args) {
        ServiceLauncher.run("business", 1112, BusinessConfiguration.class, args);
    }
}
