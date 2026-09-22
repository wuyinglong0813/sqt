package com.tradepass.module.contract.job;
import com.tradepass.module.contract.framework.callback.FadadaCallbackRecovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!${tradepass.jobs.xxl.enabled:false} || !${tradepass.services.split:false}")
public class LocalCallbackRecoveryScheduler {
    private final FadadaCallbackRecovery recovery;
    public LocalCallbackRecoveryScheduler(FadadaCallbackRecovery recovery) { this.recovery = recovery; }

    @Scheduled(fixedDelayString = "${tradepass.fadada.callback-retry-delay-ms:30000}", initialDelay = 30000)
    public void recover() { recovery.recover(); }
}
