package com.tradepass.framework.runtime.config;

import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.framework.flyway.config.FlywaySafetyConfig;
import com.tradepass.framework.mybatis.config.IdentifierConfiguration;
import com.tradepass.framework.rpc.config.DistributedTransactionConfiguration;
import com.tradepass.framework.storage.config.StorageProperties;

import com.tradepass.framework.runtime.core.DomainComponentRegistrar;
import com.tradepass.framework.runtime.core.DomainFailureAdvice;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.mybatis.spring.annotation.MapperScan;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableAsync
@EnableScheduling
@Import({TransportConfiguration.class, RuntimeWebConfiguration.class,
        OwnedDatabaseConfiguration.class, DomainFailureAdvice.class,
        com.tradepass.framework.rpc.config.DomainRpcConfiguration.class,
        com.tradepass.framework.rpc.config.DistributedTransactionConfiguration.class,
        com.tradepass.framework.mybatis.config.IdentifierConfiguration.class, com.tradepass.framework.storage.config.StorageProperties.class,
        com.tradepass.framework.fadada.config.FadadaProperties.class, com.tradepass.framework.flyway.config.FlywaySafetyConfig.class})
public class BusinessRuntimeConfiguration {
    @Bean static DomainComponentRegistrar domainRegistrar(Environment environment) { return new DomainComponentRegistrar(environment); }

    @Bean static org.springframework.beans.factory.config.BeanPostProcessor requireSplitCollaborators(Environment environment) {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                if (!environment.getProperty("tradepass.services.split", Boolean.class, false)) return bean;
                org.springframework.util.ReflectionUtils.doWithFields(bean.getClass(), field -> {
                    String type = field.getType().getName();
                    if (field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)
                            && type.startsWith("com.tradepass.") && (type.contains(".api.") || type.contains(".port."))) {
                        org.springframework.util.ReflectionUtils.makeAccessible(field);
                        if (field.get(bean) == null) throw new IllegalStateException("Missing split-service collaborator: " + name + "." + field.getName());
                    }
                });
                return bean;
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "tradepass.services.split", havingValue = "false", matchIfMissing = true)
    @MapperScan(basePackages = {"com.tradepass.module.identity.dal.mysql", "com.tradepass.module.contract.dal.mysql",
            "com.tradepass.module.trade.dal.mysql", "com.tradepass.module.settlement.dal.mysql", "com.tradepass.framework.audit.core"}, markerInterface = BaseMapper.class)
    static class SharedDatabaseMappers { }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "tradepass.services.split", havingValue = "true")
    @MapperScan(basePackages = {"com.tradepass.module.${tradepass.runtime.role}.dal.mysql", "com.tradepass.framework.audit.core"}, markerInterface = BaseMapper.class)
    static class OwnedDatabaseMappers { }
}
