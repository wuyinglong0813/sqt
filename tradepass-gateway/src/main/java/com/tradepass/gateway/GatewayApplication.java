package com.tradepass.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }

    @Bean RouteLocator publicRoutes(RouteLocatorBuilder builder, Environment env) {
        var routes = builder.routes();
        route(routes, env, "settlement", 1114, "/api/contract-attachments/**", "/api/contracts/*/attachments", "/api/contracts/*/attachments/**",
                "/api/reconciliation-accounts", "/api/reconciliation-accounts/**", "/api/reconciliation-statements", "/api/reconciliation-statements/**");
        route(routes, env, "trade", 1113, "/api/orders", "/api/orders/**", "/api/approvals/**", "/api/bilateral-actions", "/api/bilateral-actions/**",
                "/api/document-templates", "/api/document-templates/**", "/api/trade-documents/**", "/api/sales-orders/**", "/api/inventory/**",
                "/api/warehouses", "/api/contracts/*/documents", "/api/contracts/*/logistics-documents", "/api/contracts/*/logistics-documents/**",
                "/api/logistics-documents/**", "/api/contracts/*/memo", "/api/project-ledgers", "/api/project-ledgers/**", "/api/home/**", "/api/rankings/**");
        route(routes, env, "contract", 1112, "/api/contracts", "/api/contracts/**", "/api/contract-templates", "/api/contract-templates/**",
                "/api/contract-template-categories", "/api/contract-template-categories/**", "/api/fadada/callback");
        route(routes, env, "file", 1115, "/api/files/**");
        route(routes, env, "identity", 1111, "/api/auth/**", "/api/me", "/api/me/**", "/api/companies", "/api/companies/**",
                "/api/company-certifications/**", "/api/counterparties", "/api/roles", "/api/roles/**", "/api/permissions",
                "/api/authorizations", "/api/authorizations/**", "/api/verifications/**", "/api/seals", "/api/ca/**", "/api/dev/**", "/api/fadada/**", "/tcb_probe");
        return routes.build();
    }

    private void route(RouteLocatorBuilder.Builder routes, Environment env, String role, int port, String... paths) {
        routes.route(role, r -> r.path(paths).filters(f -> f
                .removeRequestHeader("X-TradePass-Internal-Key")
                .removeRequestHeader("x-wx-openid")
                .removeRequestHeader("x-wx-unionid")
                .removeRequestHeader("x-wx-appid"))
                .uri(env.getProperty("tradepass.services." + role + "-url", "http://127.0.0.1:" + port)));
    }
}
