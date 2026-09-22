package com.tradepass.module.contract.framework.callback;
import com.tradepass.module.contract.dal.mysql.signing.FadadaCallbackEventMapper;

import com.tradepass.framework.fadada.config.FadadaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class FadadaCallbackRecovery {
    private static final Logger log = LoggerFactory.getLogger(FadadaCallbackRecovery.class);
    private final FadadaCallbackEventMapper eventMapper;
    private final FadadaCallbackProcessor processor;
    private final FadadaProperties properties;

    public FadadaCallbackRecovery(FadadaCallbackEventMapper eventMapper,
                                  FadadaCallbackProcessor processor, FadadaProperties properties) {
        this.eventMapper = eventMapper;
        this.processor = processor;
        this.properties = properties;
    }

    public void recover() {
        if (!properties.isEnabled()) return;
        for (Long id : eventMapper.findDue(LocalDateTime.now())) {
            try {
                processor.process(id);
            } catch (RuntimeException exception) {
                // A failed persistence operation leaves the lease recoverable by a later scan.
                log.error("Electronic signature callback recovery failed: eventId={}", id, exception);
            }
        }
    }
}
