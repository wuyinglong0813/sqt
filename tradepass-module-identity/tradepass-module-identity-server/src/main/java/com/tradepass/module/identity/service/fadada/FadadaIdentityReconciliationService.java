package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaUserIdentityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Low-frequency current-state queries also recover events never received by this application. */
@Service
public class FadadaIdentityReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(FadadaIdentityReconciliationService.class);
    private final FadadaProperties properties;
    private final FadadaUserIdentityMapper users;
    private final FadadaCorpIdentityMapper companies;
    private final FadadaPersonalIdentityService personal;
    private final FadadaCompanyService corporate;

    public FadadaIdentityReconciliationService(FadadaProperties properties, FadadaUserIdentityMapper users,
            FadadaCorpIdentityMapper companies, FadadaPersonalIdentityService personal, FadadaCompanyService corporate) {
        this.properties = properties; this.users = users; this.companies = companies;
        this.personal = personal; this.corporate = corporate;
    }

    @Scheduled(fixedDelayString = "${tradepass.fadada.identity-reconcile-delay-ms:60000}", initialDelay = 60000)
    public void reconcile() {
        if (!properties.isEnabled()) return;
        var now = LocalDateTime.now().withNano(0);
        var verifiedBefore = now.minusMinutes(15);
        var pendingBefore = now.minusMinutes(2);
        var warningBefore = now.minusMinutes(1);
        for (var user : users.findForReconciliation(verifiedBefore, pendingBefore)) {
            var before = "VERIFIED".equals(user.getLocalStatus()) ? verifiedBefore : pendingBefore;
            if (users.claimReconciliation(user.getId(), now, before) != 1) continue;
            try { personal.syncByClientUserId(user.getClientUserId()); }
            catch (RuntimeException failure) { log.warn("Personal authorization reconciliation deferred: identityId={}", user.getId()); }
        }
        for (var company : companies.findForReconciliation(verifiedBefore, pendingBefore, warningBefore)) {
            var before = company.getSealSyncWarning() != null && !company.getSealSyncWarning().isBlank() ? warningBefore
                    : "VERIFIED".equals(company.getLocalStatus()) ? verifiedBefore : pendingBefore;
            if (companies.claimReconciliation(company.getId(), now, before) != 1) continue;
            try { corporate.sync(company.getCompanyId()); }
            catch (RuntimeException failure) { log.warn("Corporate authorization reconciliation deferred: identityId={}", company.getId()); }
        }
    }
}
