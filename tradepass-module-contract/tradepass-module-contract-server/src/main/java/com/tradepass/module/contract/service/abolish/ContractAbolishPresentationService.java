package com.tradepass.module.contract.service.abolish;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.trade.api.bilateral.BilateralStateOperations;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Display progress without changing the legal contract state before electronic abolishment. */
@Service
public class ContractAbolishPresentationService {
    private final FadadaContractSignTaskMapper tasks;
    private final BilateralStateOperations bilateral;

    public ContractAbolishPresentationService(FadadaContractSignTaskMapper tasks, BilateralStateOperations bilateral) {
        this.tasks = tasks;
        this.bilateral = bilateral;
    }

    public Map<Long, String> statusTexts(List<TradeContractDO> contracts) {
        var active = contracts.stream().filter(c -> "ACTIVE".equals(c.getStatus())).toList();
        if (active.isEmpty()) return Map.of();
        var versions = new HashMap<Long, Integer>();
        active.forEach(c -> versions.put(c.getId(), c.getVersionNo() == null ? 1 : c.getVersionNo()));
        var ids = List.copyOf(versions.keySet());
        var approved = new HashSet<>(bilateral.approvedVoidContracts(ids));
        var result = new HashMap<Long, String>();
        for (var id : approved) if (versions.containsKey(id)) result.put(id, "待签署作废协议");
        for (var task : tasks.selectList(new LambdaQueryWrapper<FadadaContractSignTaskDO>()
                .select(FadadaContractSignTaskDO::getContractId, FadadaContractSignTaskDO::getVersionNo,
                        FadadaContractSignTaskDO::getProviderStatus, FadadaContractSignTaskDO::getAbolishedSignTaskId)
                .in(FadadaContractSignTaskDO::getContractId, ids))) {
            if (!java.util.Objects.equals(versions.get(task.getContractId()),
                    task.getVersionNo() == null ? 1 : task.getVersionNo())) continue;
            String status = task.getProviderStatus() == null ? "" : task.getProviderStatus();
            if ("ABOLISH_CREATION_UNCERTAIN".equals(status)) {
                result.put(task.getContractId(), "作废任务结果待核实");
            } else if (status.startsWith("ABOLISH_") && ContractAbolishRecoveryService.stopped(status.substring(8))) {
                result.put(task.getContractId(), "作废签署已中止");
            } else if ("revoked".equalsIgnoreCase(status)) {
                result.put(task.getContractId(), "作废结果待同步");
            } else if ((task.getAbolishedSignTaskId() != null && !task.getAbolishedSignTaskId().isBlank())
                    || "abolishing".equals(status) || status.startsWith("ABOLISH_")) {
                result.put(task.getContractId(), "作废签署中");
            }
        }
        return result;
    }
}
