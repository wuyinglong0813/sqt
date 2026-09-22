package com.tradepass.module.contract.service.abolish;

import com.tradepass.module.contract.api.abolish.ContractAbolishRecoveryOperations;
import com.tradepass.module.contract.api.abolish.ContractAbolishRecoveryOperations.*;
import com.tradepass.module.trade.api.bilateral.BilateralStateOperations;
import com.tradepass.module.trade.api.bilateral.BilateralStateOperations.*;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Runs only while accepting the counterparty's RESUME request. */
@Service
public class ContractAbolishRecoveryServiceImpl implements ContractAbolishRecoveryService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.trade.api.bilateral.BilateralStateOperations bilateralState;

    private final TradeContractMapper contracts;
    private final FadadaContractSignTaskMapper tasks;
    private final FadadaSigningGateway gateway;
    private final AccessControlOperations access;
    private final JdbcTemplate jdbc;

    public ContractAbolishRecoveryServiceImpl(TradeContractMapper contracts,
                                          FadadaContractSignTaskMapper tasks,
                                          FadadaSigningGateway gateway,
                                          AccessControlOperations access, JdbcTemplate jdbc) {
        this.contracts = contracts;
        this.tasks = tasks;
        this.gateway = gateway;
        this.access = access;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void resumeAfterBilateralApproval(Long contractId) {
        long companyId = AuthContext.requireCompanyId();
        access.requirePermission(companyId, "contract_sign");
        TradeContractDO contract = contracts.selectByIdForUpdate(contractId);
        if (contract == null || (!Long.valueOf(companyId).equals(contract.getCompanyId())
                && !Long.valueOf(companyId).equals(contract.getCounterpartyCompanyId()))) {
            throw new BusinessException("合同不存在");
        }
        if (!"ACTIVE".equals(contract.getStatus())) {
            throw new BusinessException("当前合同不能恢复履约，请先同步签署状态");
        }
        int version = contract.getVersionNo() == null ? 1 : contract.getVersionNo();
        FadadaContractSignTaskDO task = tasks.selectOne(new LambdaQueryWrapper<FadadaContractSignTaskDO>()
                .eq(FadadaContractSignTaskDO::getContractId, contractId)
                .eq(FadadaContractSignTaskDO::getVersionNo, version).last("LIMIT 1 FOR UPDATE"));
        if (task == null || !hasText(task.getSignTaskId())) {
            throw new BusinessException("找不到原合同电子签署记录，不能恢复履约");
        }
        if (!jdbc.queryForList("""
                SELECT id FROM fadada_abolish_creation_intent
                WHERE contract_id = ? AND version_no = ? AND status = 'UNCONFIRMED' FOR UPDATE
                """, String.class, contractId, version).isEmpty()) {
            throw new BusinessException("作废任务创建结果尚未确认，请先刷新签署状态，不能恢复履约");
        }
        String abolishId = task.getAbolishedSignTaskId();
        if ("ABOLISH_CREATION_UNCERTAIN".equals(task.getProviderStatus())) {
            throw new BusinessException("作废任务创建结果尚未确认，不能恢复履约，请先核实电子签记录");
        }
        if (hasText(abolishId)) {
            String status = status(abolishId);
            requireNotCompleted(status);
            if (!stopped(status)) {
                gateway.cancel(abolishId, "双方同意取消作废并恢复合同履约");
                status = status(abolishId);
                requireNotCompleted(status);
                if (!stopped(status)) {
                    throw new BusinessException("作废签署尚未确认终止，合同保持只读，请稍后重试");
                }
            }
        }
        // A stopped child task alone does not establish that the original is still valid.
        if (!"task_finished".equalsIgnoreCase(status(task.getSignTaskId()))) {
            throw new BusinessException("无法确认原合同仍为已完成签署状态，不能恢复履约");
        }
        if (hasText(abolishId)) {
            requireStopped(status(abolishId));
            jdbc.update("""
                    INSERT INTO fadada_cancelled_abolish_task
                    (sign_task_id, contract_id, version_no, original_sign_task_id)
                    VALUES (?, ?, ?, ?)
                    """, abolishId, contractId, version, task.getSignTaskId());
        }
        int taskChanged = tasks.update(new LambdaUpdateWrapper<FadadaContractSignTaskDO>()
                .eq(FadadaContractSignTaskDO::getId, task.getId())
                .set(FadadaContractSignTaskDO::getAbolishedSignTaskId, null)
                .set(FadadaContractSignTaskDO::getProviderStatus, "task_finished")
                .set(FadadaContractSignTaskDO::getInitiatorSignStatus, "signed")
                .set(FadadaContractSignTaskDO::getCounterpartySignStatus, "signed")
                .set(FadadaContractSignTaskDO::getLastError, ""));
        if (taskChanged != 1) throw new BusinessException("签署任务状态已变化，合同保持只读");
        int changed = bilateralState != null ? bilateralState.cancelApprovedVoids(contractId) : jdbc.update("""
                UPDATE bilateral_action_request SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP
                WHERE contract_id = ? AND biz_type = 'CONTRACT' AND action_type = 'VOID'
                  AND status = 'APPROVED'
                """, contractId);
        if (changed == 0) throw new BusinessException("作废申请状态已变化，请刷新后重试");
    }

    private String status(String id) {
        var status = gateway.status(id);
        if (status == null || !hasText(status.status())) throw new BusinessException("无法确认电子签署状态，合同保持只读");
        return status.status();
    }

    private void requireNotCompleted(String status) {
        if ("task_finished".equalsIgnoreCase(status) || "sign_completed".equalsIgnoreCase(status)
                || "revoked".equalsIgnoreCase(status)) {
            throw new BusinessException("作废协议已经完成签署，不能恢复履约，请同步合同状态");
        }
    }

    private void requireStopped(String status) {
        requireNotCompleted(status);
        if (!stopped(status)) throw new BusinessException("作废签署终止状态尚未确认，合同保持只读");
    }

    public static boolean stopped(String status) {
        return "task_terminated".equalsIgnoreCase(status) || "expired".equalsIgnoreCase(status)
                || "task_expired".equalsIgnoreCase(status);
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
}
