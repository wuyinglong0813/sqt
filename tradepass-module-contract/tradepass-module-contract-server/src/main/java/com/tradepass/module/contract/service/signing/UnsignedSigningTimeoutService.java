package com.tradepass.module.contract.service.signing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.trade.api.approval.ApprovalOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class UnsignedSigningTimeoutService {
    private static final Logger log = LoggerFactory.getLogger(UnsignedSigningTimeoutService.class);
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private final TradeContractMapper contracts;
    private final FadadaContractSignTaskMapper tasks;
    private final SigningCancellationIntentStore intents;
    private final SigningCancellationProcessor processor;
    private final AuditLogService audit;
    private final ApprovalOperations notifications;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public UnsignedSigningTimeoutService(TradeContractMapper contracts, FadadaContractSignTaskMapper tasks,
            SigningCancellationIntentStore intents, SigningCancellationProcessor processor, AuditLogService audit,
            ApprovalOperations notifications, PlatformTransactionManager manager) {
        this(contracts, tasks, intents, processor, audit, notifications, manager, Clock.systemUTC());
    }

    public UnsignedSigningTimeoutService(TradeContractMapper contracts, FadadaContractSignTaskMapper tasks,
            SigningCancellationIntentStore intents, SigningCancellationProcessor processor, AuditLogService audit,
            ApprovalOperations notifications, PlatformTransactionManager manager, Clock clock) {
        this.contracts=contracts; this.tasks=tasks; this.intents=intents; this.processor=processor;
        this.audit=audit; this.notifications=notifications; this.tx=new TransactionTemplate(manager); this.clock=clock;
    }

    public void runBatch() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), BEIJING);
        for (var task : tasks.findStoppedUnsignedRefundCandidates(20)) {
            try {
                intents.request(task, SigningCancellationProcessor.REFUND_RECOVERY_REASON, false);
            } catch (RuntimeException failure) {
                log.warn("Unsigned signature refund discovery deferred: taskId={}", task.getId());
            }
        }
        for (var task : tasks.findUnsignedTimeoutCandidates(now, now.minusDays(28), 20)) {
            try {
                intents.request(task, SigningCancellationProcessor.TIMEOUT_REASON, true);
            } catch (RuntimeException failure) {
                log.warn("Unsigned signature timeout discovery deferred: taskId={}", task.getId());
            }
        }
        for (var intent : intents.due(now, 20)) {
            if (!intents.claim(intent.signTaskId(), now)) continue;
            try {
                tx.executeWithoutResult(ignored -> process(intent, now));
            } catch (RuntimeException failure) {
                // Never print provider exception bodies, URLs, tokens or parties' personal data.
                log.warn("Signature cancellation recovery deferred: contractId={}, version={}",
                        intent.contractId(), intent.version());
            }
        }
    }

    private void process(SigningCancellationIntentStore.Intent candidate, LocalDateTime now) {
        // Same lock order as signing, callbacks, manual withdrawal and resubmission.
        var contract = contracts.selectOne(new LambdaQueryWrapper<TradeContractDO>()
                .eq(TradeContractDO::getId,candidate.contractId()).last("LIMIT 1 FOR UPDATE"));
        var task = tasks.selectOne(new LambdaQueryWrapper<FadadaContractSignTaskDO>()
                .eq(FadadaContractSignTaskDO::getContractId,candidate.contractId())
                .eq(FadadaContractSignTaskDO::getVersionNo,candidate.version())
                .eq(FadadaContractSignTaskDO::getSignTaskId,candidate.signTaskId()).last("LIMIT 1 FOR UPDATE"));
        // A manual request may have taken over or completed while this worker waited for the locks.
        var intent = intents.pending(candidate.signTaskId());
        if (intent == null) return;
        if (task == null) throw new IllegalStateException("签署撤回任务记录待恢复");
        if (intent.unsignedOnly() && (contract == null || !"PENDING".equals(contract.getStatus())
                || version(contract) != intent.version() || task.getArchivedAt() != null
                || task.getAbolishedSignTaskId() != null)) {
            processor.restoreOwnedBlocks(task);
            intents.complete(intent.signTaskId(), true);
            return;
        }
        if (intent.unsignedOnly() && !due(task, now)) return;
        var result = processor.process(task, intent);
        if (tasks.updateById(task) != 1) throw new IllegalStateException("签署任务状态已变化");
        if (intent.unsignedOnly() && result == SigningCancellationProcessor.Result.CANCELLED) {
            int changed = contracts.update(null, new LambdaUpdateWrapper<TradeContractDO>()
                    .eq(TradeContractDO::getId, contract.getId()).eq(TradeContractDO::getVersionNo,intent.version())
                    .eq(TradeContractDO::getStatus,"PENDING").set(TradeContractDO::getStatus,"CANCELLED")
                    .set(TradeContractDO::getApprovedBy,null).set(TradeContractDO::getApprovedAt,null));
            if (changed != 1) throw new IllegalStateException("合同状态已变化");
            audit.logAs(contract.getCompanyId(),0,"CONTRACT",contract.getId(),"SIGNING_TIMEOUT",
                    SigningCancellationProcessor.TIMEOUT_REASON+" "+contract.getContractNo());
            notifyParty(contract.getCompanyId(),contract);
            if (contract.getCounterpartyCompanyId()!=null) notifyParty(contract.getCounterpartyCompanyId(),contract);
        } else if (!intent.unsignedOnly() && "发起方撤回合同".equals(intent.reason())
                && result == SigningCancellationProcessor.Result.CANCELLED && contract != null
                && "PENDING".equals(contract.getStatus()) && version(contract)==intent.version()) {
            // Recover a user's withdrawal if provider cancellation committed but the HTTP/local transaction failed.
            int changed = contracts.update(null,new LambdaUpdateWrapper<TradeContractDO>()
                    .eq(TradeContractDO::getId,contract.getId()).eq(TradeContractDO::getVersionNo,intent.version())
                    .eq(TradeContractDO::getStatus,"PENDING").set(TradeContractDO::getStatus,"CANCELLED")
                    .set(TradeContractDO::getApprovedBy,null).set(TradeContractDO::getApprovedAt,null));
            if (changed!=1) throw new IllegalStateException("合同状态已变化");
            audit.logAs(contract.getCompanyId(),0,"CONTRACT",contract.getId(),"CANCEL_RECOVERED",
                    "恢复发起方撤回合同 "+contract.getContractNo());
        }
        intents.complete(intent.signTaskId(), result == SigningCancellationProcessor.Result.SIGNED);
    }

    private void notifyParty(long companyId, TradeContractDO contract) {
        notifications.recordResult(companyId,contract.getCompanyId(),"CONTRACT",contract.getId(),null,
                "CANCELLED",SigningCancellationProcessor.TIMEOUT_REASON,
                "合同 "+contract.getContractNo()+" 创建签署任务满一个月且双方均未签署，已自动撤回。",null);
    }

    static boolean due(FadadaContractSignTaskDO task, LocalDateTime now) {
        LocalDateTime created = task.getPreparedAt() == null ? task.getCreatedAt() : task.getPreparedAt();
        return created != null && !created.plusMonths(1).isAfter(now);
    }

    private static int version(TradeContractDO contract) { return contract.getVersionNo()==null?1:contract.getVersionNo(); }
}
