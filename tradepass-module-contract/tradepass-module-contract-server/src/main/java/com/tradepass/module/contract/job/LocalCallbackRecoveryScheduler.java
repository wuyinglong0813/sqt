package com.tradepass.module.contract.job;
import com.tradepass.module.contract.framework.callback.FadadaCallbackRecovery;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Conditional(LocalCallbackRecoveryScheduler.WithoutSplitXxlJobs.class)
public class LocalCallbackRecoveryScheduler {
    private final FadadaCallbackRecovery recovery;
    public LocalCallbackRecoveryScheduler(FadadaCallbackRecovery recovery) { this.recovery = recovery; }

    @Scheduled(fixedDelayString = "${tradepass.fadada.callback-retry-delay-ms:30000}", initialDelay = 30000)
    public void recover() { recovery.recover(); }

    /** Environment-only so DomainComponentRegistrar's registry-less scan can evaluate it. */
    static class WithoutSplitXxlJobs implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            return !environment.getProperty("tradepass.jobs.xxl.enabled", Boolean.class, false)
                    || !environment.getProperty("tradepass.services.split", Boolean.class, false);
        }
    }
}
