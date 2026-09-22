package com.tradepass.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;

@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.web-application-type=reactive", "spring.cloud.sentinel.enabled=true",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
                "spring.cloud.sentinel.filter.enabled=false", "tradepass.services.identity-url=http://127.0.0.1:1",
                "management.server.port=0"})
class GatewayProtectionTest {
    @Autowired WebTestClient http;
    @AfterEach void clear() { GatewayRuleManager.loadRules(Set.of()); }

    @Test void blockedRouteReturnsTheExistingJsonEnvelope() {
        GatewayRuleManager.loadRules(Set.of(new GatewayFlowRule("identity").setCount(0)));
        http.get().uri("/api/me").exchange().expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody().jsonPath("$.code").isEqualTo(429).jsonPath("$.message").isEqualTo("请求过于频繁，请稍后重试");
    }

    @Test void protectionDoesNotExposeInternalOrManagementPaths() {
        GatewayRuleManager.loadRules(Set.of(new GatewayFlowRule("identity").setCount(0)));
        for (String path : new String[]{"/internal/storage/put", "/actuator/env"}) {
            http.get().uri(path).exchange().expectStatus().isNotFound();
        }
    }
}
