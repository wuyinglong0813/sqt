package com.tradepass.module.contract.framework.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tradepass.module.contract.framework.callback.CallbackDispatcher;
import com.tradepass.module.contract.framework.callback.FadadaCallbackProcessor;
import com.tradepass.module.contract.framework.callback.FadadaCallbackRecovery;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real broker and real authenticated XXL executor; business processors are covered separately. */
@EnabledIfSystemProperty(named = "tradepass.test.rocketmq.server", matches = ".+")
class CallbackBrokerAndJobTest {
    @Test void brokerDeliveryAndAuthenticatedExecutorReachOriginalHandlers() throws Exception {
        String namesrv = System.getProperty("tradepass.test.rocketmq.server");
        assertTrue(namesrv.matches("(127\\.0\\.0\\.1|localhost):[0-9]+"));
        String token = UUID.randomUUID() + "-isolated-job-token";
        AtomicInteger registrations = new AtomicInteger();
        HttpServer admin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        admin.createContext("/", exchange -> {
            boolean authenticated = token.equals(exchange.getRequestHeaders().getFirst("XXL-JOB-ACCESS-TOKEN"));
            if (authenticated && exchange.getRequestURI().getPath().endsWith("/registry")) registrations.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = ("{\"code\":" + (authenticated ? "200" : "500") + ",\"msg\":\"test\",\"content\":null}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        admin.start();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var properties = new LinkedHashMap<String, Object>();
        properties.put("tradepass.messaging.rocketmq.enabled", true);
        properties.put("tradepass.messaging.rocketmq.name-server", namesrv);
        properties.put("tradepass.messaging.rocketmq.callback-topic", "tradepass-ci-callbacks");
        properties.put("tradepass.messaging.rocketmq.producer-group", "ci-producer-" + UUID.randomUUID());
        properties.put("tradepass.messaging.rocketmq.consumer-group", "ci-consumer-" + UUID.randomUUID());
        properties.put("tradepass.jobs.xxl.enabled", true);
        properties.put("tradepass.jobs.xxl.admin-addresses", "http://127.0.0.1:" + admin.getAddress().getPort());
        properties.put("tradepass.jobs.xxl.access-token", token);
        properties.put("tradepass.jobs.xxl.ip", "127.0.0.1");
        properties.put("tradepass.jobs.xxl.port", port);
        properties.put("tradepass.jobs.xxl.log-path", java.nio.file.Path.of("target/xxl-test-logs").toAbsolutePath().toString());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-infrastructure", properties));
            context.register(Handlers.class, CallbackMessagingConfiguration.class, CallbackJobConfiguration.class);
            context.refresh();
            var dispatcher = context.getBean(CallbackDispatcher.class);
            dispatcher.dispatch(91L);
            dispatcher.dispatch(91L);
            verify(context.getBean(FadadaCallbackProcessor.class), timeout(30_000).times(2)).process(91L);
            var json = new ObjectMapper();
            awaitExecutor(port, token);
            assertNotEquals(200, json.readTree(post(port, "invalid", "/beat", "{}").body()).path("code").asInt());
            Map<String, Object> trigger = Map.of("jobId", 1, "executorHandler", "fadadaCallbackRecovery",
                    "executorBlockStrategy", "SERIAL_EXECUTION", "executorTimeout", 0, "logId", 1,
                    "logDateTime", System.currentTimeMillis(), "glueType", "BEAN", "glueUpdatetime", 0,
                    "broadcastIndex", 0, "broadcastTotal", 1);
            var response = post(port, token, "/run", json.writeValueAsString(trigger));
            assertEquals(200, json.readTree(response.body()).path("code").asInt(), response.body());
            verify(context.getBean(FadadaCallbackRecovery.class), timeout(10_000)).recover();
            assertTrue(registrations.get() > 0, "Executor must register with the authenticated administrator");
        } finally { admin.stop(0); }
    }

    static void awaitExecutor(int port, String token) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try { if (post(port, token, "/beat", "{}").statusCode() == 200) return; }
            catch (java.io.IOException ignored) { }
            Thread.sleep(100);
        }
        fail("XXL executor did not start");
    }
    static HttpResponse<String> post(int port, String token, String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("XXL-JOB-ACCESS-TOKEN", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Configuration(proxyBeanMethods = false)
    static class Handlers {
        @Bean FadadaCallbackProcessor processor() { return mock(FadadaCallbackProcessor.class); }
        @Bean FadadaCallbackRecovery recovery() { return mock(FadadaCallbackRecovery.class); }
        @Bean MeterRegistry metrics() { return new SimpleMeterRegistry(); }
    }
}
