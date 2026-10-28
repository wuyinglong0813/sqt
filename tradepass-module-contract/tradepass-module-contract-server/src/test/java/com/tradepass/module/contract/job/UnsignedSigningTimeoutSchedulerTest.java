package com.tradepass.module.contract.job;

import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.contract.service.signing.UnsignedSigningTimeoutService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class UnsignedSigningTimeoutSchedulerTest {
    @Test void disabledProviderDoesNotDiscoverOrCancelAnyTasks() {
        var timeouts = mock(UnsignedSigningTimeoutService.class);
        new UnsignedSigningTimeoutScheduler(new FadadaProperties(), timeouts).reclaim();

        verifyNoInteractions(timeouts);
    }

    @Test void enablingTheProviderRunsOneBatchAndDisablingItStopsSubsequentBatches() {
        var properties = new FadadaProperties();
        var timeouts = mock(UnsignedSigningTimeoutService.class);
        var scheduler = new UnsignedSigningTimeoutScheduler(properties, timeouts);
        properties.setEnabled(true);
        scheduler.reclaim();
        properties.setEnabled(false);
        scheduler.reclaim();

        verify(timeouts, times(1)).runBatch();
    }
}
