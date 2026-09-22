package com.tradepass.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.cloud.sentinel.enabled", havingValue = "true")
public class GatewayProtectionConfiguration {
    @Bean("sentinel-json-gw-flow-converter")
    com.alibaba.cloud.sentinel.datasource.converter.JsonConverter gatewayFlowConverter() {
        return new com.alibaba.cloud.sentinel.datasource.converter.JsonConverter(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
                com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule.class);
    }

    @Bean @Order(-1)
    SentinelGatewayFilter sentinelGatewayFilter() { return new SentinelGatewayFilter(); }

    @Bean @Order(-2)
    SentinelGatewayBlockExceptionHandler sentinelBlockHandler(ObjectProvider<ViewResolver> views,
                                                              ServerCodecConfigurer codecs) {
        GatewayCallbackManager.setBlockHandler((exchange, error) -> ServerResponse.status(429)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":429,\"message\":\"请求过于频繁，请稍后重试\",\"data\":null}"));
        return new SentinelGatewayBlockExceptionHandler(views.orderedStream().toList(), codecs);
    }
}
