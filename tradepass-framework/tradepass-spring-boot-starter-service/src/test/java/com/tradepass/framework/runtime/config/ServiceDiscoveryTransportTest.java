package com.tradepass.framework.runtime.config;

import com.tradepass.framework.runtime.core.InternalAccessFilter;
import com.tradepass.framework.runtime.core.InternalContracts;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.sun.net.httpserver.HttpServer;
import feign.FeignException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ServiceDiscoveryTransportTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"tradepass.runtime.role=file", "tradepass.services.identity-url=", "tradepass.services.file-url=",
                "tradepass.services.internal-key=discovery-private-test-key-1234567890",
                "spring.cloud.sentinel.enabled=true", "feign.sentinel.enabled=true"})
class ServiceDiscoveryTransportTest {
    static final AtomicInteger FIRST = new AtomicInteger(), SECOND = new AtomicInteger();
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicInteger INVALID_KEY = new AtomicInteger();
    static final HttpServer A = server(FIRST), B = server(SECOND);
    @Autowired InternalContracts.StorageClient storage;
    @Autowired InternalContracts.IdentityClient identity;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
            "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"})
    @Import(TransportConfiguration.class)
    static class App { }

    @DynamicPropertySource static void instances(DynamicPropertyRegistry registry) {
        for (String name : List.of("tradepass-file", "tradepass-identity")) {
            registry.add("spring.cloud.discovery.client.simple.instances." + name + "[0].uri", () -> "http://127.0.0.1:" + A.getAddress().getPort());
            registry.add("spring.cloud.discovery.client.simple.instances." + name + "[1].uri", () -> "http://127.0.0.1:" + B.getAddress().getPort());
        }
    }

    @BeforeEach void reset() { FIRST.set(0); SECOND.set(0); INVALID_KEY.set(0); STATUS.set(200); FlowRuleManager.loadRules(List.of()); }
    @AfterEach void clearRules() { FlowRuleManager.loadRules(List.of()); }
    @AfterAll static void stop() { A.stop(0); B.stop(0); }

    @Test void resolvesServiceNamesAndBalancesAcrossTwoHttpInstances() {
        for (int i = 0; i < 6; i++) assertThat(identity.resolve("Bearer test", null).userId()).isEqualTo(123);
        assertThat(FIRST.get()).isEqualTo(3); assertThat(SECOND.get()).isEqualTo(3);
        assertThat(INVALID_KEY.get()).isZero();
    }

    @Test void sentinelRejectsFileWritesBeforeTheyReachEitherInstance() {
        var rule = new FlowRule("POST:http://tradepass-module-file/tradepass-module-file-server/internal/storage/put");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS); rule.setCount(0);
        FlowRuleManager.loadRules(List.of(rule));
        assertThatThrownBy(() -> storage.put(new InternalContracts.PutObject("key", new byte[]{1}, "image/png", "sha")))
                .isInstanceOf(FeignException.ServiceUnavailable.class);
        assertThat(FIRST.get() + SECOND.get()).isZero();
    }

    @Test void downstreamFailuresAreNotRetriedByFeignOrLoadBalancer() {
        STATUS.set(503);
        assertThatThrownBy(() -> storage.put(new InternalContracts.PutObject("key", new byte[]{1}, "image/png", "sha")))
                .isInstanceOf(FeignException.ServiceUnavailable.class);
        assertThat(FIRST.get() + SECOND.get()).isEqualTo(1);
    }

    @Test void sentinelFallbackKeepsTheOriginalUnauthorizedResponse() {
        STATUS.set(401);
        assertThatThrownBy(() -> identity.resolve("bad", null)).isInstanceOf(FeignException.Unauthorized.class)
                .hasMessageContaining("原鉴权错误");
        assertThat(FIRST.get() + SECOND.get()).isEqualTo(1);
    }

    static HttpServer server(AtomicInteger hits) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal", exchange -> {
                hits.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                if (!"discovery-private-test-key-1234567890".equals(exchange.getRequestHeaders().getFirst(InternalAccessFilter.HEADER))) INVALID_KEY.incrementAndGet();
                String body = STATUS.get() == 200 ? "{\"userId\":123,\"companyId\":456}" : "{\"code\":401,\"message\":\"原鉴权错误\",\"data\":null}";
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(STATUS.get(), data.length); exchange.getResponseBody().write(data); exchange.close();
            });
            server.start(); return server;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
