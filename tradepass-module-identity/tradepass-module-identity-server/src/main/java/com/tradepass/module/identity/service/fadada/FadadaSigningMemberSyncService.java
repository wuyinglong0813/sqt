package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaSigningMemberSyncMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static com.tradepass.framework.common.exception.SigningErrorCodes.*;

/** Durable, leased preparation work; no remote call runs inside the local lifecycle transaction. */
@Service
public class FadadaSigningMemberSyncService {
    private static final Logger log = LoggerFactory.getLogger(FadadaSigningMemberSyncService.class);
    private final FadadaSigningMemberSyncMapper queue;
    private final FadadaSigningAuthorizationService authorization;
    private final FadadaProperties properties;
    private final TransactionTemplate enqueueTransaction;

    public FadadaSigningMemberSyncService(FadadaSigningMemberSyncMapper queue, FadadaSigningAuthorizationService authorization,
            FadadaProperties properties, PlatformTransactionManager transactions) {
        this.queue = queue; this.authorization = authorization; this.properties = properties;
        this.enqueueTransaction = new TransactionTemplate(transactions);
        this.enqueueTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener
    public void request(SigningMemberSyncRequested event) {
        if (!properties.isEnabled() || event == null
                || event.companyId() == null && event.targetUserId() == null) return;
        Runnable enqueue = () -> {
            try {
                // afterCommit still has the completed transaction's bound resources.
                // A new transaction is required to make this queue write durable.
                enqueueTransaction.executeWithoutResult(ignored -> queue.enqueue(event.companyId(),
                        event.targetUserId(), event.actorUserId(), event.source()));
            } catch (RuntimeException failure) {
                // Periodic backfill recovers this gap; the local approval/identity stays successful.
                log.warn("Signing member enqueue deferred: companyId={}, targetUserId={}, actorUserId={}",
                        event.companyId(), event.targetUserId(), event.actorUserId());
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { enqueue.run(); }
            });
        } else enqueue.run();
    }

    @Scheduled(fixedDelayString = "${tradepass.fadada.member-sync-delay-ms:30000}", initialDelay = 15000)
    public void reconcile() {
        if (!properties.isEnabled()) return;
        queue.backfill();
        queue.recheckLegacyReady();
        for (var candidate : queue.due()) {
            String token = UUID.randomUUID().toString();
            if (queue.claim(candidate.companyId(), candidate.targetUserId(), token) != 1) continue;
            try {
                // No AuthContext is synthesized. The server-only entry point rechecks
                // the target's current local authority and exact remote personal identity.
                authorization.reconcileLocalSigningAuthorization(candidate.companyId(), candidate.targetUserId());
                // Legacy READY meant membership only. Do not expose it as a delivered seal grant.
                finish(candidate, token, "SIGNING_READY", 0, "", 86400);
            } catch (BusinessException failure) {
                int code = failure.getCode();
                String status = switch (code) {
                    case COMPANY_AUTHORIZATION_REQUIRED, COMPANY_IDENTITY_REQUIRED -> "WAITING_COMPANY_AUTHORIZATION";
                    case MEMBER_ACTIVATION_REQUIRED -> "ACTIVATION_REQUIRED";
                    case PERSONAL_IDENTITY_REQUIRED -> "WAITING_PERSONAL_IDENTITY";
                    case SEAL_AUTHORIZATION_REQUIRED -> "WAITING_SEAL_AUTHORIZATION";
                    case SEAL_CONFIGURATION_REQUIRED -> "WAITING_SEAL_CONFIGURATION";
                    case ADMIN_REVIEW_REQUIRED -> "ADMIN_REVIEW_REQUIRED";
                    default -> "PENDING";
                };
                finish(candidate, token, status, code, message(failure), "PENDING".equals(status) ? 120 : 600);
            } catch (RuntimeException unavailable) {
                finish(candidate, token, "PENDING", 500, "电子签成员同步暂未完成，后台将继续核对", 120);
            }
        }
    }

    private void finish(FadadaSigningMemberSyncMapper.Candidate candidate, String token,
                        String status, int code, String message, int retrySeconds) {
        queue.finish(candidate.companyId(), candidate.targetUserId(), token, status, code, message, retrySeconds);
        if (code != 0) log.info("Signing member preparation pending: companyId={}, targetUserId={}, actorUserId={}, code={}",
                candidate.companyId(), candidate.targetUserId(), candidate.actorUserId(), code);
    }

    private String message(BusinessException failure) {
        String value = failure.getMessage();
        return value == null ? "电子签成员同步待确认" : value.substring(0, Math.min(512, value.length()));
    }
}
