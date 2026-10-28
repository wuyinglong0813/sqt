package com.tradepass.module.contract.service.signing;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.service.membership.MembershipService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** External cancellation is recoverable through an intent committed before any provider write. */
@Service
public class SigningCancellationProcessor {
    public static final String TIMEOUT_REASON = "超过默认签署时间已撤回";
    public static final String REFUND_RECOVERY_REASON = "恢复未签署撤回任务的原签署额度";
    public enum Result { CANCELLED, SIGNED }
    private final FadadaSigningGateway gateway;
    private final SigningCancellationIntentStore intents;
    private final MembershipService membership;

    public SigningCancellationProcessor(FadadaSigningGateway gateway, SigningCancellationIntentStore intents,
                                        MembershipService membership) {
        this.gateway = gateway;
        this.intents = intents;
        this.membership = membership;
    }

    /** Caller holds the contract and current task locks, in that order. */
    public Result process(FadadaContractSignTaskDO task, SigningCancellationIntentStore.Intent intent) {
        if (!Objects.equals(task.getSignTaskId(), intent.signTaskId())
                || !Objects.equals(task.getContractId(), intent.contractId())
                || version(task) != intent.version()) throw new BusinessException("签署撤回记录与合同版本不一致");
        try {
            var state = status(task.getSignTaskId());
            if (REFUND_RECOVERY_REASON.equals(intent.reason()) && !stopped(state.status())) {
                // Historical repair is read-only toward the provider; it must never cancel a live task.
                throw new BusinessException("历史撤回任务尚未确认终止，暂不返还额度");
            }
            if (completed(state.status())) {
                restoreOwnedBlocks(task);
                apply(task, state);
                return Result.SIGNED;
            }
            if (intent.unsignedOnly() && signedEvidence(task, state)) {
                restoreOwnedBlocks(task);
                apply(task, state);
                return Result.SIGNED;
            }
            if (intent.unsignedOnly() && !unsigned(task, state)) {
                throw new BusinessException("双方未签署的证据尚未齐全，保留原签署任务");
            }
            if (!stopped(state.status())) {
                if (intent.unsignedOnly()) {
                    // A read followed by cancel is unsafe: a party could sign between them.
                    // Block both actors, then read again, before cancelling an unsigned task.
                    var actors = actors(task, state);
                    for (String id : expectedActors(task)) {
                        String block = actors.get(id).blockStatus();
                        if ("blocked".equals(block)) continue; // Preserve someone else's block.
                        if (!"unblocked".equals(block)) throw new BusinessException("无法确认签署阻塞状态，稍后重试");
                        intents.rememberOwnedActor(task.getSignTaskId(), id);
                        gateway.blockActor(task.getSignTaskId(), id);
                    }
                    state = status(task.getSignTaskId());
                    if (signedEvidence(task, state)) {
                        restoreOwnedBlocks(task);
                        apply(task, state);
                        return Result.SIGNED;
                    }
                    if (!unsigned(task, state) || actors(task, state).values().stream()
                            .anyMatch(a -> !"blocked".equals(a.blockStatus()))) {
                        throw new BusinessException("未确认双方签署均已暂停，保留原任务");
                    }
                }
                gateway.cancel(task.getSignTaskId(), intent.reason());
                state = status(task.getSignTaskId());
            }
            if (!stopped(state.status())) throw new BusinessException("签署撤销结果尚未确认，请稍后重试");
            if (intent.unsignedOnly() && !unsigned(task, state)) {
                throw new BusinessException("终止后的签署证据需要核实，暂不退回额度");
            }
            if (!unsigned(task, state) && !signedEvidence(task, state)) {
                // Do not retire a manual cancellation whose refund eligibility is still unknown.
                throw new BusinessException("终止后的签署证据需要核实，暂不退回额度");
            }
            // Refund only after a fresh, stopped task proves both exact actors never signed.
            if (unsigned(task, state)) membership.refundCancelledUnsigned(
                    task.getContractId(), version(task), task.getSignTaskId());
            restoreOwnedBlocks(task);
            apply(task, state);
            task.setLastError(intent.reason());
            return Result.CANCELLED;
        } catch (RuntimeException failure) {
            try {
                restoreOwnedBlocks(task);
            } catch (RuntimeException cleanupFailure) {
                // Keep ownership in the durable intent. The scheduled recovery retries it.
                failure.addSuppressed(new BusinessException("临时签署暂停尚未恢复，将自动重试"));
            }
            throw failure;
        }
    }

    /** May also be used when a queued intent is retired because the contract version changed. */
    public void restoreOwnedBlocks(FadadaContractSignTaskDO task) {
        List<String> owned = intents.ownedActors(task.getSignTaskId());
        if (owned.isEmpty()) return;
        var state = status(task.getSignTaskId());
        if (stopped(state.status()) || completed(state.status())) {
            for (String id : owned) intents.forgetOwnedActor(task.getSignTaskId(), id);
            return;
        }
        var actors = actors(task, state);
        for (String id : owned) {
            var actor = actors.get(id);
            if (actor == null) throw new BusinessException("临时签署暂停参与方待核实");
            if ("blocked".equals(actor.blockStatus())) {
                gateway.unblockActor(task.getSignTaskId(), id);
                var refreshed = actors(task, status(task.getSignTaskId())).get(id);
                if (refreshed == null || !"unblocked".equals(refreshed.blockStatus())) {
                    throw new BusinessException("临时签署暂停尚未恢复");
                }
            } else if (!"unblocked".equals(actor.blockStatus())) {
                throw new BusinessException("临时签署暂停状态待核实");
            }
            intents.forgetOwnedActor(task.getSignTaskId(), id);
        }
    }

    private FadadaSigningGateway.TaskStatus status(String id) {
        var state = gateway.status(id);
        if (state == null || !id.equals(state.signTaskId()) || !hasText(state.status())) {
            throw new BusinessException("无法确认签署任务状态，请稍后重试");
        }
        return state;
    }

    private boolean unsigned(FadadaContractSignTaskDO task, FadadaSigningGateway.TaskStatus state) {
        if (signedEvidence(task, state)) return false;
        try {
            return actors(task, state).values().stream()
                    .allMatch(a -> "wait_sign".equals(a.signStatus()) && !hasText(a.signTime()));
        } catch (BusinessException incomplete) {
            return false;
        }
    }

    private boolean signedEvidence(FadadaContractSignTaskDO task, FadadaSigningGateway.TaskStatus state) {
        return ContractSigningProgress.signed(task.getInitiatorSignStatus())
                || ContractSigningProgress.signed(task.getCounterpartySignStatus())
                || (state.actors() != null && state.actors().stream().filter(Objects::nonNull)
                    .anyMatch(a -> ContractSigningProgress.signed(a.signStatus()) || hasText(a.signTime())));
    }

    private Map<String, FadadaSigningGateway.ActorStatus> actors(FadadaContractSignTaskDO task,
                                                               FadadaSigningGateway.TaskStatus state) {
        var expected = expectedActors(task);
        var result = new HashMap<String, FadadaSigningGateway.ActorStatus>();
        if (state.actors() == null) throw new BusinessException("签署参与方证据不完整");
        for (var actor : state.actors()) {
            if (actor == null || !expected.contains(actor.actorId()) || result.put(actor.actorId(), actor) != null) {
                throw new BusinessException("签署参与方证据不匹配");
            }
        }
        if (result.size() != 2) throw new BusinessException("签署参与方证据不完整");
        return result;
    }

    private List<String> expectedActors(FadadaContractSignTaskDO task) {
        if (!hasText(task.getInitiatorActorId()) || !hasText(task.getCounterpartyActorId())
                || task.getInitiatorActorId().equals(task.getCounterpartyActorId())) {
            throw new BusinessException("本地签署参与方关联不完整");
        }
        return List.of(task.getInitiatorActorId(), task.getCounterpartyActorId());
    }

    private void apply(FadadaContractSignTaskDO task, FadadaSigningGateway.TaskStatus state) {
        task.setProviderStatus(state.status());
        if (state.actors() == null) return;
        for (var actor : state.actors()) {
            if (actor == null) continue;
            if (Objects.equals(actor.actorId(), task.getInitiatorActorId())
                    && !ContractSigningProgress.signed(task.getInitiatorSignStatus())) task.setInitiatorSignStatus(actor.signStatus());
            if (Objects.equals(actor.actorId(), task.getCounterpartyActorId())
                    && !ContractSigningProgress.signed(task.getCounterpartySignStatus())) task.setCounterpartySignStatus(actor.signStatus());
        }
    }

    static boolean stopped(String value) {
        return "task_terminated".equalsIgnoreCase(value) || "expired".equalsIgnoreCase(value)
                || "task_expired".equalsIgnoreCase(value);
    }
    private static boolean completed(String value) {
        return "task_finished".equalsIgnoreCase(value) || "sign_completed".equalsIgnoreCase(value)
                || "revoked".equalsIgnoreCase(value);
    }
    private static int version(FadadaContractSignTaskDO task) { return task.getVersionNo() == null ? 1 : task.getVersionNo(); }
    private static boolean hasText(String text) { return text != null && !text.isBlank(); }
}
