package com.tradepass.framework.runtime.core;

import org.springframework.boot.SpringApplication;
import java.util.Map;

public final class ServiceLauncher {
    private ServiceLauncher() { }
    public static void run(String role, int port, Class<?> configuration, String[] args) {
        if (!RouteOwnership.ROLES.contains(role)) throw new IllegalArgumentException("Unknown service role");
        SpringApplication app = new SpringApplication(configuration);
        if (role.equals("file")) app.setAdditionalProfiles("observability", "microservice", role);
        else app.setAdditionalProfiles("observability", "microservice", "split", role);
        app.addInitializers(context -> {
            Map<String, Object> fixed = new java.util.HashMap<>();
            fixed.put("tradepass.runtime.role", role);
            fixed.put("wechat.cloud-open-api-enabled", false);
            String workerBase = context.getEnvironment().getProperty("TRADEPASS_IDS_WORKER_BASE");
            if (workerBase != null) {
                String pod = context.getEnvironment().getRequiredProperty("TRADEPASS_POD_NAME");
                if (!pod.matches(java.util.regex.Pattern.quote(role) + "-[0-5]")) {
                    throw new IllegalArgumentException("An owned StatefulSet ordinal 0..5 is required for ID allocation");
                }
                int expected = java.util.List.of("identity", "contract", "trade", "settlement", "file").indexOf(role) * 6;
                int base = Integer.parseInt(workerBase);
                if (base != expected) throw new IllegalArgumentException("Incorrect worker range for " + role);
                fixed.put("tradepass.ids.worker-id", base + Integer.parseInt(pod.substring(pod.lastIndexOf('-') + 1)));
            }
            if (!role.equals("file")) fixed.put("tradepass.services.split", true);
            if (role.equals("file")) fixed.put("management.endpoint.health.group.readiness.include", "readinessState");
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("service-identity", fixed));
        });
        app.setDefaultProperties(Map.of("tradepass.runtime.role", role, "tradepass.runtime.port", port,
                "tradepass.runtime.management-port", port + 10000));
        app.run(args);
    }
}
