package com.tradepass.framework.common.core;

import java.util.Set;

/** Which domain a process serves. {@code business} hosts the trading domains in one JVM. */
public final class HostedRoles {
    private static final Set<String> BUSINESS = Set.of("contract", "trade", "settlement", "file");

    private HostedRoles() { }

    public static boolean hosts(String role, String owner) {
        if (role == null || owner == null) return false;
        if (role.equals(owner)) return true;
        return "business".equals(role) && BUSINESS.contains(owner);
    }
}
