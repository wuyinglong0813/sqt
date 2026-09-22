package com.tradepass.module.contract.framework.config;

import com.tradepass.module.contract.framework.callback.FadadaCallbackRecovery;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "tradepass.jobs.xxl.enabled", havingValue = "true")
public class CallbackJobConfiguration {
    @Bean XxlJobSpringExecutor callbackJobExecutor(
            @Value("${tradepass.jobs.xxl.admin-addresses}") String admins,
            @Value("${tradepass.jobs.xxl.access-token}") String token,
            @Value("${tradepass.jobs.xxl.address:}") String address,
            @Value("${tradepass.jobs.xxl.ip:}") String ip,
            @Value("${tradepass.jobs.xxl.port:9998}") int port,
            @Value("${tradepass.jobs.xxl.log-path:/tmp/tradepass-jobs}") String logPath) {
        if (admins.isBlank() || token.length() < 32) throw new IllegalStateException("XXL-JOB requires admin addresses and an environment-specific token of at least 32 characters");
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(admins);
        executor.setAppname("tradepass-contract");
        executor.setAccessToken(token);
        executor.setAddress(address);
        executor.setIp(ip);
        executor.setPort(port);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(7);
        return executor;
    }

    @Bean CallbackRecoveryJob callbackRecoveryJob(FadadaCallbackRecovery recovery, MeterRegistry metrics) {
        return new CallbackRecoveryJob(recovery, metrics);
    }

    public static class CallbackRecoveryJob {
        private final FadadaCallbackRecovery recovery;
        private final MeterRegistry metrics;
        public CallbackRecoveryJob(FadadaCallbackRecovery recovery, MeterRegistry metrics) {
            this.recovery = recovery;
            this.metrics = metrics;
        }
        @XxlJob("fadadaCallbackRecovery")
        public void recover() {
            var sample = io.micrometer.core.instrument.Timer.start(metrics);
            try {
                recovery.recover();
                metrics.counter("tradepass.callback.recovery", "result", "completed").increment();
            } catch (RuntimeException failure) {
                metrics.counter("tradepass.callback.recovery", "result", "failed").increment();
                throw failure;
            } finally {
                sample.stop(metrics.timer("tradepass.callback.recovery.duration"));
            }
        }
    }
}
