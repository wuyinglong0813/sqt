package com.tradepass.framework.runtime.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercise Spring config precedence with the same documents as the Nacos importer.
 * Only transport is substituted with classpath imports; no database or Nacos is contacted.
 */
class NacosApplicationConfigTest {
    private ApplicationContextRunner runner(String role) {
        return new ApplicationContextRunner()
                .withPropertyValues("tradepass.runtime.role=" + role,
                        "tradepass.runtime.port=1112", "tradepass.runtime.management-port=11112",
                        "TRADEPASS_IDS_WORKER_ID=2",
                        "spring.application.name=tradepass-" + role,
                        "spring.profiles.active=observability,microservice,split,core",
                        "spring.config.additional-location=classpath:core-nacos/bootstrap.yml")
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    @Test void importedNativePropertiesWorkWithoutApplicationEnvironmentVariables() {
        runner("identity").run(context -> {
            assertThat(context).hasNotFailed();
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:mysql://127.0.0.1:3306/test_identity");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("test-identity-secret");
            assertThat(env.getProperty("wechat.app-secret")).isEqualTo("test-wechat-secret");
            assertThat(env.getProperty("tradepass.fadada.enabled", Boolean.class)).isTrue();
            assertThat(env.getProperty("tradepass.fadada.app-secret")).isEqualTo("test-fdd-secret");
            assertThat(env.getProperty("tradepass.services.contract-url")).isEmpty();
            assertThat(env.getProperty("tradepass.ids.datacenter-id", Integer.class)).isEqualTo(2);
            assertThat(env.getProperty("spring.cloud.nacos.discovery.enabled", Boolean.class)).isTrue();
            assertThat(env.getProperty("spring.cloud.nacos.config.password")).isEqualTo("test-nacos-password");
        });
    }

    @Test void businessGetsOnlyItsDatabaseAndItsNativeMessagingAndStorageSettings() {
        runner("business").run(context -> {
            assertThat(context).hasNotFailed();
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("test-business-secret");
            assertThat(env.getProperty("tradepass.messaging.rocketmq.name-server")).isEqualTo("127.0.0.1:9876");
            assertThat(env.getProperty("tradepass.storage.oss.access-key-id")).isEqualTo("test-oss-id");
            assertThat(env.getProperty("tradepass.storage.oss.access-key-secret")).isEqualTo("test-oss-secret");
        });
    }

    @Test void gatewayRetainsDiscoveryUrisInsteadOfEmptyFeignUrls() {
        runner("gateway").run(context -> {
            assertThat(context).hasNotFailed();
            var env = context.getEnvironment();
            assertThat(env.getProperty("tradepass.services.identity-url")).isEqualTo("lb://tradepass-identity");
            assertThat(env.getProperty("tradepass.services.contract-url")).isEqualTo("lb://tradepass-business");
        });
    }
}
