package com.tradepass.module.contract.service.signing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Batch presentation of the current task; does not change the editable contract lifecycle. */
@Service
public class ContractSigningPresentationService {
    private static final String UNSIGNED_TIMEOUT_TEXT = "超过默认签署时间已撤回";
    private final FadadaContractSignTaskMapper tasks;
    public ContractSigningPresentationService(FadadaContractSignTaskMapper tasks) { this.tasks = tasks; }
    public record Progress(String status, String text) {}

    public Map<Long, Progress> statuses(List<TradeContractDO> contracts, long viewerCompanyId) {
        var versions = new HashMap<Long, Integer>();
        var initiators = new HashMap<Long, Long>();
        var lifecycles = new HashMap<Long, String>();
        contracts.stream().filter(c -> "PENDING".equals(c.getStatus()) || "CANCELLED".equals(c.getStatus()))
                .forEach(c -> {
                    versions.put(c.getId(), c.getVersionNo() == null ? 1 : c.getVersionNo());
                    initiators.put(c.getId(), c.getCompanyId());
                    lifecycles.put(c.getId(), c.getStatus());
                });
        if (versions.isEmpty()) return Map.of();
        var result = new HashMap<Long, Progress>();
        versions.keySet().stream().filter(id -> "PENDING".equals(lifecycles.get(id)))
                .forEach(id -> result.put(id, new Progress("WAITING_AUTH",
                ContractSigningProgress.partyText("WAITING_AUTH", null, null,
                        java.util.Objects.equals(initiators.get(id), viewerCompanyId)))));
        for (var task : tasks.selectList(new LambdaQueryWrapper<FadadaContractSignTaskDO>()
                .select(FadadaContractSignTaskDO::getContractId, FadadaContractSignTaskDO::getVersionNo,
                        FadadaContractSignTaskDO::getProviderStatus, FadadaContractSignTaskDO::getArchivedAt,
                        FadadaContractSignTaskDO::getLastError,
                        FadadaContractSignTaskDO::getInitiatorSignStatus, FadadaContractSignTaskDO::getCounterpartySignStatus)
                .in(FadadaContractSignTaskDO::getContractId, versions.keySet()))) {
            if (!java.util.Objects.equals(versions.get(task.getContractId()),
                    task.getVersionNo() == null ? 1 : task.getVersionNo()) || task.getProviderStatus() == null) continue;
            if ("CANCELLED".equals(lifecycles.get(task.getContractId()))) {
                if (UNSIGNED_TIMEOUT_TEXT.equals(task.getLastError())
                        && List.of("task_terminated", "expired", "task_expired").contains(task.getProviderStatus())) {
                    result.put(task.getContractId(), new Progress("SIGNING_TIMEOUT", UNSIGNED_TIMEOUT_TEXT));
                }
                continue;
            }
            String text = switch (task.getProviderStatus()) {
                case "CREATING" -> "正在准备签署文件";
                case "CREATE_FAILED" -> "签署任务准备失败";
                case "task_terminated" -> "签署已终止";
                case "expired", "task_expired" -> "签署已过期";
                case "sign_progress", "finish_creation" -> "签署中";
                case "sign_completed" -> "双方已签署，正在归档";
                case "task_finished" -> task.getArchivedAt() == null ? "签署结果确认中" : "签署结果待同步";
                default -> null;
            };
            boolean initiator = java.util.Objects.equals(initiators.get(task.getContractId()), viewerCompanyId);
            String partyText = ContractSigningProgress.partyText(task.getProviderStatus(),
                    initiator ? task.getInitiatorSignStatus() : task.getCounterpartySignStatus(),
                    initiator ? task.getCounterpartySignStatus() : task.getInitiatorSignStatus(), initiator);
            if (partyText != null) text = partyText;
            result.put(task.getContractId(), new Progress(task.getProviderStatus(), text));
        }
        return result;
    }
}
