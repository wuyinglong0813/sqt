package com.tradepass.module.contract.job;

import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.service.signing.FadadaContractSigningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Query current provider state even when no callback reached the application. */
@Service
public class SigningStateReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(SigningStateReconciliationScheduler.class);
    private final FadadaContractSignTaskMapper tasks;
    private final FadadaContractSigningService signing;
    private final FadadaProperties properties;

    public SigningStateReconciliationScheduler(FadadaContractSignTaskMapper tasks,
                                               FadadaContractSigningService signing, FadadaProperties properties) {
        this.tasks = tasks;
        this.signing = signing;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${tradepass.fadada.state-reconcile-delay-ms:60000}", initialDelay = 60000)
    public void reconcile() {
        if (!properties.isEnabled()) return;
        LocalDateTime now = LocalDateTime.now().withNano(0);
        for (var task : tasks.findDueReconciliation(now.minusMinutes(5), now.minusHours(12), 20)) {
            // Persist the next polling window before I/O. A failed or crashed attempt is
            // retried later; concurrent contract instances cannot all query the same task.
            if (tasks.claimReconciliation(task.getId(), task.getUpdatedAt(), now) != 1) continue;
            try {
                signing.syncBySignTaskId(task.getSignTaskId());
            } catch (RuntimeException exception) {
                log.warn("Electronic signature state reconciliation deferred: taskId={}", task.getId());
            }
        }
    }
}
