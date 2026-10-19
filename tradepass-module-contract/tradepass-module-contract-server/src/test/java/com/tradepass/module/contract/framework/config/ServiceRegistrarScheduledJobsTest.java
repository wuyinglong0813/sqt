package com.tradepass.module.contract.framework.config;

import com.tradepass.framework.runtime.core.DomainComponentRegistrar;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercise the production registrar: a plain component can otherwise silently lose its schedule. */
class ServiceRegistrarScheduledJobsTest {
    @ParameterizedTest
    @ValueSource(strings = {"contract", "business", "identity"})
    void registersReconciliationJobsOnlyInTheirHostingRole(String role) {
        var environment = new MockEnvironment().withProperty("tradepass.services.split", "true")
                .withProperty("tradepass.runtime.role", role);
        var registry = new DefaultListableBeanFactory();

        new DomainComponentRegistrar(environment).postProcessBeanDefinitionRegistry(registry);

        boolean hostsContract = "contract".equals(role) || "business".equals(role);
        assertThat(registry.containsBeanDefinition("signingStateReconciliationScheduler")).isEqualTo(hostsContract);
        if (hostsContract) assertThat(registry.getBeanDefinition("signingStateReconciliationScheduler").getBeanClassName())
                .isEqualTo("com.tradepass.module.contract.job.SigningStateReconciliationScheduler");
        assertThat(registry.containsBeanDefinition("fadadaIdentityReconciliationService")).isEqualTo("identity".equals(role));
        assertThat(registry.containsBeanDefinition("fadadaSigningMemberSyncService")).isEqualTo("identity".equals(role));
    }

    @ParameterizedTest
    @CsvSource({"identity,true", "identity,false", "business,true", "business,false"})
    void memberPreparationScheduleIsAnExplicitRootInBothAssemblies(String role, boolean split) {
        var environment = new MockEnvironment().withProperty("tradepass.services.split", String.valueOf(split))
                .withProperty("tradepass.runtime.role", role);
        var registry = new DefaultListableBeanFactory();
        new DomainComponentRegistrar(environment).postProcessBeanDefinitionRegistry(registry);
        assertThat(registry.containsBeanDefinition("fadadaSigningMemberSyncService")).isEqualTo("identity".equals(role));
    }
}
