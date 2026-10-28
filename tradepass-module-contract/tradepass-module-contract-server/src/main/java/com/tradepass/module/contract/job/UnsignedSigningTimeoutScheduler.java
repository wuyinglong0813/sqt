package com.tradepass.module.contract.job;

import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.contract.service.signing.UnsignedSigningTimeoutService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UnsignedSigningTimeoutScheduler {
    private final FadadaProperties properties;
    private final UnsignedSigningTimeoutService timeouts;
    public UnsignedSigningTimeoutScheduler(FadadaProperties properties, UnsignedSigningTimeoutService timeouts) {
        this.properties=properties; this.timeouts=timeouts;
    }
    @Scheduled(fixedDelayString="${tradepass.fadada.unsigned-timeout-check-delay-ms:60000}", initialDelay=60000)
    public void reclaim() {
        if (properties.isEnabled()) timeouts.runBatch();
    }
}
