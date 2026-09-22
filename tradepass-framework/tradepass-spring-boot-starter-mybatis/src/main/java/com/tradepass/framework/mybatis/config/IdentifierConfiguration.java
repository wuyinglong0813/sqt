package com.tradepass.framework.mybatis.config;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IdentifierConfiguration {
    @Bean
    IdentifierGenerator identifierGenerator(
            @Value("${tradepass.ids.worker-id:#{null}}") Long workerId,
            @Value("${tradepass.ids.datacenter-id:#{null}}") Long datacenterId) {
        if ((workerId == null) != (datacenterId == null)) {
            throw new IllegalArgumentException("ID worker-id 和 datacenter-id 必须同时配置");
        }
        IdentifierGenerator generator = workerId == null
                ? DefaultIdentifierGenerator.getInstance()
                : new DefaultIdentifierGenerator(workerId, datacenterId);
        IdWorker.setIdentifierGenerator(generator);
        return generator;
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer longValuesAsStrings() {
        return builder -> builder.serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}
