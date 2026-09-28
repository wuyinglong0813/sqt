package com.tradepass.module.contract.job;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCallbackRecoverySchedulerTest {
    @Test
    void registryLessScanSelectsSchedulerWithoutXxlJobs() {
        assertThat(scan(new MockEnvironment().withProperty("tradepass.services.split", "true")
                .withProperty("tradepass.jobs.xxl.enabled", "false")))
                .contains(LocalCallbackRecoveryScheduler.class.getName());
    }

    @Test
    void splitServicesWithXxlJobsSkipScheduler() {
        assertThat(scan(new MockEnvironment().withProperty("tradepass.services.split", "true")
                .withProperty("tradepass.jobs.xxl.enabled", "true")))
                .doesNotContain(LocalCallbackRecoveryScheduler.class.getName());
    }

    private static java.util.List<String> scan(MockEnvironment environment) {
        return new ClassPathScanningCandidateComponentProvider(true, environment)
                .findCandidateComponents("com.tradepass.module.contract.job").stream()
                .map(BeanDefinition::getBeanClassName).toList();
    }
}
