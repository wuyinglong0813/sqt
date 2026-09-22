package com.tradepass.framework.runtime.core;

import com.tradepass.framework.web.core.controller.ProbeController;

import java.lang.reflect.Method;
import java.util.Set;

/** Public controllers live in their owning service; only the probe is shared. */
public final class RouteOwnership {
    public static final Set<String> ROLES = Set.of("identity", "contract", "trade", "settlement", "file");
    private RouteOwnership() { }

    public static String owner(Class<?> type, Method method) {
        if (type.getName().equals("com.tradepass.framework.web.core.controller.ProbeController")) return "all";
        String name = type.getPackageName();
        // Role-gated Feign endpoints live outside the module packages. The bean is created only for its owning role.
        if (name.startsWith("com.tradepass.framework.rpc.core")) return "all";
        return ROLES.stream().filter(role -> name.startsWith("com.tradepass.module." + role + "."))
                .findFirst().orElseThrow(() -> new IllegalStateException("Unassigned controller: " + type.getName()));
    }

    public static boolean serves(String role, Class<?> type, Method method) {
        String owner = owner(type, method);
        return "all".equals(owner) || role.equals(owner);
    }
}
