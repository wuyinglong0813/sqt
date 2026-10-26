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
    private final FadadaContractSignTaskMapper tasks;
    public ContractSigningPresentationService(FadadaContractSignTaskMapper tasks) { this.tasks = tasks; }
    public record Progress(String status, String text) {}

    public Map<Long, Progress> statuses(List<TradeContractDO> contracts, long viewerCompanyId) {
        var versions = new HashMap<Long, Integer>();
        var initiators = new HashMap<Long, Long>();
        contracts.stream().filter(c -> "PENDING".equals(c.getStatus()))
                .forEach(c -> {
                    versions.put(c.getId(), c.getVersionNo() == null ? 1 : c.getVersionNo());
                    initiators.put(c.getId(), c.getCompanyId());
                });
        if (versions.isEmpty()) return Map.of();
        var result = new HashMap<Long, Progress>();
        for (var task : tasks.selectList(new LambdaQueryWrapper<FadadaContractSignTaskDO>()
                .select(FadadaContractSignTaskDO::getContractId, FadadaContractSignTaskDO::getVersionNo,
                        FadadaContractSignTaskDO::getProviderStatus, FadadaContractSignTaskDO::getArchivedAt,
                        FadadaContractSignTaskDO::getInitiatorSignStatus, FadadaContractSignTaskDO::getCounterpartySignStatus)
                .in(FadadaContractSignTaskDO::getContractId, versions.keySet()))) {
            if (!java.util.Objects.equals(versions.get(task.getContractId()),
                    task.getVersionNo() == null ? 1 : task.getVersionNo()) || task.getProviderStatus() == null) continue;
            String text = switch (task.getProviderStatus()) {
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
                    initiator ? task.getCounterpartySignStatus() : task.getInitiatorSignStatus());
            if (partyText != null) text = partyText;
            result.put(task.getContractId(), new Progress(task.getProviderStatus(), text));
        }
        return result;
    }
}
