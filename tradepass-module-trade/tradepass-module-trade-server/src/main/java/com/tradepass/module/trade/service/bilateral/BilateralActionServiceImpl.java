package com.tradepass.module.trade.service.bilateral;

import com.tradepass.module.contract.api.state.ContractStateOperations;
import com.tradepass.module.contract.api.state.ContractStateOperations.*;
import com.tradepass.module.settlement.api.attachment.AttachmentStateOperations;
import com.tradepass.module.settlement.api.attachment.AttachmentStateOperations.*;
import com.tradepass.module.trade.api.bilateral.BilateralActionOperations;
import com.tradepass.module.trade.api.bilateral.BilateralActionOperations.*;
import com.tradepass.module.trade.service.approval.ApprovalService;
import com.tradepass.module.trade.service.inventory.SalesOrderInventoryService;

import com.tradepass.module.contract.api.abolish.ContractAbolishRecoveryOperations;
import com.tradepass.module.contract.api.abolish.ContractAbolishRecoveryOperations.*;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations;
import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations.*;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations.*;

import com.tradepass.framework.mybatis.core.ApplicationIds;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class BilateralActionServiceImpl implements BilateralActionService {
    public static final String CONTRACT = "CONTRACT";
    public static final String ATTACHMENT = "ATTACHMENT";
    public static final String BUSINESS_DOCUMENT = "BUSINESS_DOCUMENT";
    public static final String END = "END";
    public static final String VOID = "VOID";
    public static final String RESUME = "RESUME";

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.contract.api.state.ContractStateOperations contractState;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.settlement.api.attachment.AttachmentStateOperations attachmentState;

    private final JdbcTemplate jdbc;
    private final ContractReader contractMapper;
    private final AccessControlOperations accessControlService;
    private final AuditLogService auditLogService;
    private final ReconciliationAccountOperations reconciliationAccountService;
    private final SalesOrderInventoryService inventoryService;
    private final ApprovalService approvalService;
    private ContractAbolishRecoveryOperations abolishRecoveryService;

    @Autowired
    public void setAbolishRecoveryService(ContractAbolishRecoveryOperations service) {
        this.abolishRecoveryService = service;
    }

    public BilateralActionServiceImpl(JdbcTemplate jdbc,
                                  ContractReader contractMapper,
                                  AccessControlOperations accessControlService,
                                  AuditLogService auditLogService,
                                  ReconciliationAccountOperations reconciliationAccountService,
                                  SalesOrderInventoryService inventoryService,
                                  ApprovalService approvalService) {
        this.jdbc = jdbc;
        this.contractMapper = contractMapper;
        this.accessControlService = accessControlService;
        this.auditLogService = auditLogService;
        this.reconciliationAccountService = reconciliationAccountService;
        this.inventoryService = inventoryService;
        this.approvalService = approvalService;
    }

    @Transactional
    public Map<String, Object> request(String bizType, Long bizId, String actionType,
                                       String reason, boolean riskConfirmed) {
        long companyId = AuthContext.requireCompanyId();
        String normalizedBizType = normalizeBizType(bizType);
        String normalizedAction = normalizeAction(actionType);
        String safeReason = requireReason(reason);
        Target target = requireTarget(normalizedBizType, bizId, companyId, true);
        requirePermission(companyId, normalizedBizType);
        validateRequest(target, normalizedAction, riskConfirmed);
        Long approverCompanyIdValue = target.contract().getCompanyId() == companyId
                ? target.contract().getCounterpartyCompanyId() : target.contract().getCompanyId();
        if (approverCompanyIdValue == null || approverCompanyIdValue <= 0) {
            throw new BusinessException("合同对方企业信息不完整");
        }
        long approverCompanyId = approverCompanyIdValue;
        Long id = ApplicationIds.next();
        try {
            jdbc.update("""
                    INSERT INTO bilateral_action_request
                    (id, contract_id, biz_type, biz_id, action_type, requester_company_id,
                     requester_user_id, approver_company_id, reason, risk_confirmed)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, target.contract().getId(), normalizedBizType, bizId, normalizedAction,
                    companyId, AuthContext.userId(), approverCompanyId, safeReason,
                    normalizedAction.equals(END) && riskConfirmed);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("该业务已有待处理的" + actionLabel(normalizedAction) + "申请");
        }
        auditLogService.log(companyId, "BILATERAL_ACTION", id, "REQUEST",
                "发起" + target.label() + actionLabel(normalizedAction) + "申请：" + safeReason);
        return findView(id, companyId);
    }

    @Transactional
    public Map<String, Object> decide(Long id, String decision, String reason) {
        long companyId = AuthContext.requireCompanyId();
        ActionRecord action = requireAction(id, true);
        if (action.approverCompanyId() != companyId) {
            throw new BusinessException("仅对方企业可以处理该申请");
        }
        requirePermission(companyId, action.bizType());
        String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(normalized) && !"REJECT".equals(normalized)) {
            throw new BusinessException("处理结果不正确");
        }
        if ("REJECT".equals(normalized)) {
            String safeReason = requireReason(reason);
            int updated = jdbc.update("""
                    UPDATE bilateral_action_request
                    SET status = 'REJECTED', decision_reason = ?, decided_by = ?, decided_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'PENDING'
                    """, safeReason, AuthContext.userId(), id);
            if (updated != 1) throw new BusinessException("申请状态已变化，请刷新后重试");
            recordResult(action, companyId, "REJECTED", "已拒绝", safeReason);
            auditLogService.log(companyId, "BILATERAL_ACTION", id, "REJECT",
                    "拒绝" + actionLabel(action.actionType()) + "申请：" + safeReason);
            return findView(id, companyId);
        }

        applyApprovedAction(action, companyId);
        int updated = jdbc.update("""
                UPDATE bilateral_action_request
                SET status = 'APPROVED', decision_reason = NULL, decided_by = ?, decided_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING'
                """, AuthContext.userId(), id);
        if (updated != 1) throw new BusinessException("申请状态已变化，请刷新后重试");
        recordResult(action, companyId, "APPROVED", "已同意", null);
        auditLogService.log(companyId, "BILATERAL_ACTION", id, "APPROVE",
                "同意" + actionLabel(action.actionType()) + "申请");
        return findView(id, companyId);
    }

    @Transactional
    public String cancel(Long id) {
        long companyId = AuthContext.requireCompanyId();
        ActionRecord action = requireAction(id, true);
        if (action.requesterCompanyId() != companyId || action.requesterUserId() != AuthContext.userId()) {
            throw new BusinessException("仅申请人可以撤回该申请");
        }
        int updated = jdbc.update("""
                UPDATE bilateral_action_request
                SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING'
                """, id);
        if (updated != 1) throw new BusinessException("申请状态已变化，请刷新后重试");
        if (approvalService != null) {
            approvalService.recordResult(action.approverCompanyId(), companyId,
                    "BILATERAL_ACTION", id, action.contractId(), "CANCELLED",
                    actionLabel(action.actionType()) + "申请已撤回",
                    "发起方已撤回申请", null);
        }
        auditLogService.log(companyId, "BILATERAL_ACTION", id, "CANCEL",
                "撤回" + actionLabel(action.actionType()) + "申请");
        return "申请已撤回";
    }

    public Map<String, Object> active(String bizType, Long bizId) {
        long companyId = AuthContext.requireCompanyId();
        Target target = requireTarget(normalizeBizType(bizType), bizId, companyId);
        List<Long> ids = CONTRACT.equals(target.bizType())
                ? jdbc.query("""
                        SELECT id FROM bilateral_action_request
                        WHERE contract_id = ? AND status = 'PENDING'
                        ORDER BY id DESC LIMIT 1
                        """, (rs, rowNum) -> rs.getLong(1), target.contract().getId())
                : jdbc.query("""
                        SELECT id FROM bilateral_action_request
                        WHERE biz_type = ? AND biz_id = ? AND status = 'PENDING'
                        ORDER BY id DESC LIMIT 1
                        """, (rs, rowNum) -> rs.getLong(1), target.bizType(), bizId);
        return ids.isEmpty() ? Map.of() : findView(ids.get(0), companyId);
    }

    public ActionState state(long companyId, String bizType, Long bizId) {
        if (bizId == null) return ActionState.empty();
        List<ActionState> rows = jdbc.query("""
                        SELECT id, action_type, requester_company_id, requester_user_id,
                               approver_company_id, reason
                        FROM bilateral_action_request
                        WHERE biz_type = ? AND biz_id = ? AND status = 'PENDING'
                        ORDER BY id DESC LIMIT 1
                        """, (rs, rowNum) -> new ActionState(
                        rs.getLong("id"), rs.getString("action_type"), rs.getString("reason"),
                        rs.getLong("requester_company_id") == companyId,
                        rs.getLong("approver_company_id") == companyId,
                        rs.getLong("requester_user_id") == AuthContext.userId()),
                normalizeBizType(bizType), bizId);
        return rows.isEmpty() ? ActionState.empty() : rows.get(0);
    }

    public void requireContractMutable(TradeContractRespDTO contract) {
        if (contract == null) throw new BusinessException("合同不存在");
        if ("COMPLETED".equals(contract.getStatus()) || "VOIDED".equals(contract.getStatus())) {
            throw new BusinessException("合同已结束或作废，仅允许查看");
        }
        Long count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM bilateral_action_request
                WHERE contract_id = ? AND status = 'PENDING'
                """, Long.class, contract.getId());
        if (count != null && count > 0) throw new BusinessException("合同正在等待双方处理，暂不允许修改");
        if (hasElectronicAbolishApproval(contract.getId())) {
            throw new BusinessException("合同正在签署作废协议，暂不允许修改");
        }
    }

    public boolean isContractReadOnly(TradeContractRespDTO contract) {
        if (contract == null || "COMPLETED".equals(contract.getStatus())
                || "VOIDED".equals(contract.getStatus())) return true;
        Long count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM bilateral_action_request
                WHERE contract_id = ? AND status = 'PENDING'
                """, Long.class, contract.getId());
        return count != null && count > 0 || hasElectronicAbolishApproval(contract.getId());
    }

    private void validateRequest(Target target, String actionType, boolean riskConfirmed) {
        if (count("""
                SELECT COUNT(1) FROM bilateral_action_request
                WHERE contract_id = ? AND status = 'PENDING'
                """, target.contract().getId()) > 0) {
            throw new BusinessException("该合同已有待处理的双方申请，请处理完成后再操作");
        }
        if (RESUME.equals(actionType)) {
            if (!CONTRACT.equals(target.bizType()) || !"ACTIVE".equals(target.status())
                    || !hasElectronicAbolishApproval(target.contract().getId())) {
                throw new BusinessException("仅已同意作废、尚未完成作废签署的合同可以申请恢复履约");
            }
            return;
        }
        if (hasElectronicAbolishApproval(target.contract().getId())) {
            throw new BusinessException("合同已进入作废协议签署，不能再发起其他处理");
        }
        if (END.equals(actionType)) {
            if (!CONTRACT.equals(target.bizType())) throw new BusinessException("仅合同可以申请结束");
            if (!"ACTIVE".equals(target.status())) throw new BusinessException("仅履约中的合同可以申请结束");
            if (!riskConfirmed) throw new BusinessException("请先确认合同结束风险提示");
            requireNoUnfinishedChildren(target.contract().getId());
            return;
        }
        if (!VOID.equals(actionType)) throw new BusinessException("申请类型不正确");
        if (CONTRACT.equals(target.bizType())) {
            if (!"ACTIVE".equals(target.status())) throw new BusinessException("仅履约中的合同可以申请作废");
            long effectiveChildren = count("""
                    SELECT COUNT(1) FROM business_document
                    WHERE contract_id = ? AND deleted_at IS NULL
                      AND status IN ('DRAFT', 'ISSUED', 'REJECTED', 'ACKNOWLEDGED', 'INBOUNDED')
                    """, target.contract().getId()) + count("""
                    SELECT COUNT(1) FROM contract_attachment
                    WHERE contract_id = ? AND deleted_at IS NULL
                      AND category IN ('PAYMENT_VOUCHER', 'INVOICE')
                      AND status IN ('PENDING_CONFIRMATION', 'REJECTED', 'APPROVED')
                    """, target.contract().getId());
            if (effectiveChildren > 0) {
                throw new BusinessException("合同仍有未清理的单据、发票或转款凭证，请先撤回、删除或作废后再处理合同");
            }
        } else if (ATTACHMENT.equals(target.bizType()) && !"APPROVED".equals(target.status())) {
            throw new BusinessException("仅已通过的资料可以申请作废");
        } else if (BUSINESS_DOCUMENT.equals(target.bizType())
                && !List.of("ACKNOWLEDGED", "INBOUNDED").contains(target.status())) {
            throw new BusinessException("仅已确认的单据可以申请作废");
        }
    }

    private void requireNoUnfinishedChildren(Long contractId) {
        long unfinished = count("""
                    SELECT COUNT(1) FROM business_document
                    WHERE contract_id = ? AND deleted_at IS NULL
                      AND status IN ('DRAFT', 'ISSUED', 'REJECTED', 'ACKNOWLEDGED')
                    FOR UPDATE
                    """, contractId) + count("""
                    SELECT COUNT(1) FROM contract_attachment
                    WHERE contract_id = ? AND deleted_at IS NULL
                      AND status IN ('PENDING_CONFIRMATION', 'REJECTED')
                    FOR UPDATE
                    """, contractId);
        if (unfinished > 0) {
            throw new BusinessException("合同仍有草稿、待确认、已拒绝或待入库资料，请先处理后再结束");
        }
    }

    private void applyApprovedAction(ActionRecord action, long approvedCompanyId) {
        Target target = requireTarget(action.bizType(), action.bizId(), approvedCompanyId, true);
        LocalDateTime approvedAt = LocalDateTime.now();
        if (CONTRACT.equals(action.bizType())) {
            if (RESUME.equals(action.actionType())) {
                if (abolishRecoveryService == null) throw new BusinessException("恢复履约服务暂不可用");
                abolishRecoveryService.resumeAfterBilateralApproval(action.contractId());
                return;
            }
            if (END.equals(action.actionType())) requireNoUnfinishedChildren(action.contractId());
            if (VOID.equals(action.actionType())) {
                Long electronicTaskCount = contractState != null ? contractState.electronicTaskCount(action.contractId()) : jdbc.queryForObject("""
                        SELECT COUNT(1) FROM fadada_contract_sign_task
                        WHERE contract_id = ? AND sign_task_id IS NOT NULL
                        """, Long.class, action.contractId());
                // New electronically signed contracts continue into the provider abolish task.
                // Historical contracts keep the original local bilateral void behavior.
                if (electronicTaskCount != null && electronicTaskCount > 0) return;
            }
            String nextStatus = VOID.equals(action.actionType()) ? "VOIDED" : "COMPLETED";
            int updated = contractState != null ? contractState.changeActiveStatus(action.bizId(), nextStatus) : jdbc.update("""
                    UPDATE trade_contract SET status = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'ACTIVE'
                    """, nextStatus, action.bizId());
            if (updated != 1) throw new BusinessException("合同状态已变化，请刷新后重试");
            return;
        }
        if (ATTACHMENT.equals(action.bizType())) {
            int updated = attachmentState != null ? attachmentState.voidApproved(action.bizId()) : jdbc.update("""
                    UPDATE contract_attachment SET status = 'VOIDED'
                    WHERE id = ? AND status = 'APPROVED' AND deleted_at IS NULL
                    """, action.bizId());
            if (updated != 1) throw new BusinessException("资料状态已变化，请刷新后重试");
            reconciliationAccountService.reverseSource(target.sourceType(), action.bizId(),
                    action.id(), AuthContext.userId(), approvedAt);
            return;
        }
        if (BUSINESS_DOCUMENT.equals(action.bizType())) {
            if ("INBOUNDED".equals(target.status())) {
                inventoryService.reverseDocumentInventory(action.bizId(), action.id(), AuthContext.userId());
            }
            reconciliationAccountService.reverseSource(target.sourceType(), action.bizId(),
                    action.id(), AuthContext.userId(), approvedAt);
            int updated = jdbc.update("""
                    UPDATE business_document SET status = 'VOIDED'
                    WHERE id = ? AND status IN ('ACKNOWLEDGED', 'INBOUNDED') AND deleted_at IS NULL
                    """, action.bizId());
            if (updated != 1) throw new BusinessException("单据状态已变化，请刷新后重试");
        }
    }

    private Target requireTarget(String bizType, Long bizId, long companyId) {
        return requireTarget(bizType, bizId, companyId, false);
    }

    private Target requireTarget(String bizType, Long bizId, long companyId, boolean forUpdate) {
        if (bizId == null) throw new BusinessException("业务 ID 不能为空");
        if (CONTRACT.equals(bizType)) {
            TradeContractRespDTO contract = forUpdate ? contractMapper.selectByIdForUpdate(bizId)
                    : contractMapper.selectById(bizId);
            if (contract == null || (!Long.valueOf(companyId).equals(contract.getCompanyId())
                    && !Long.valueOf(companyId).equals(contract.getCounterpartyCompanyId()))) {
                throw new BusinessException("合同不存在");
            }
            return new Target(bizType, bizId, contract, contract.getStatus(), "合同", "CONTRACT");
        }
        if (ATTACHMENT.equals(bizType)) {
            if (attachmentState != null) {
                var row = attachmentState.state(bizId, false);
                if (row == null) throw new BusinessException("资料不存在");
                TradeContractRespDTO contract = requireContractParty(row.contractId(), companyId);
                if (ContractAttachmentOperations.OTHER.equals(row.category())) {
                    throw new BusinessException("其它资料由上传人直接删除，无需双方作废");
                }
                return new Target(bizType, bizId, contract, row.status(), attachmentLabel(row.category()), row.category());
            }
            List<TargetRow> rows = jdbc.query("""
                            SELECT contract_id, status, category FROM contract_attachment
                            WHERE id = ? AND deleted_at IS NULL
                            """, (rs, rowNum) -> new TargetRow(rs.getLong("contract_id"),
                            rs.getString("status"), rs.getString("category")), bizId);
            if (rows.isEmpty()) throw new BusinessException("资料不存在");
            TargetRow row = rows.get(0);
            TradeContractRespDTO contract = requireContractParty(row.contractId(), companyId);
            if (ContractAttachmentOperations.OTHER.equals(row.sourceType())) {
                throw new BusinessException("其它资料由上传人直接删除，无需双方作废");
            }
            return new Target(bizType, bizId, contract, row.status(),
                    attachmentLabel(row.sourceType()), row.sourceType());
        }
        List<TargetRow> rows = jdbc.query("""
                        SELECT contract_id, status, document_type FROM business_document
                        WHERE id = ? AND deleted_at IS NULL
                        """ + (forUpdate ? " FOR UPDATE" : ""), (rs, rowNum) -> new TargetRow(rs.getLong("contract_id"),
                        rs.getString("status"), rs.getString("document_type")), bizId);
        if (rows.isEmpty()) throw new BusinessException("单据不存在");
        TargetRow row = rows.get(0);
        TradeContractRespDTO contract = requireContractParty(row.contractId(), companyId);
        return new Target(bizType, bizId, contract, row.status(),
                "RETURN_ORDER".equals(row.sourceType()) ? "退货单" : "销售单", row.sourceType());
    }

    private TradeContractRespDTO requireContractParty(Long contractId, long companyId) {
        TradeContractRespDTO contract = contractMapper.selectById(contractId);
        if (contract == null || (!Long.valueOf(companyId).equals(contract.getCompanyId())
                && !Long.valueOf(companyId).equals(contract.getCounterpartyCompanyId()))) {
            throw new BusinessException("合同不存在");
        }
        return contract;
    }

    private ActionRecord requireAction(Long id, boolean pending) {
        List<ActionRecord> rows = jdbc.query("""
                        SELECT id, contract_id, biz_type, biz_id, action_type,
                               requester_company_id, requester_user_id, approver_company_id,
                               status, reason, decision_reason, created_at
                        FROM bilateral_action_request WHERE id = ? FOR UPDATE
                        """, (rs, rowNum) -> new ActionRecord(
                        rs.getLong("id"), rs.getLong("contract_id"), rs.getString("biz_type"),
                        rs.getLong("biz_id"), rs.getString("action_type"),
                        rs.getLong("requester_company_id"), rs.getLong("requester_user_id"),
                        rs.getLong("approver_company_id"), rs.getString("status"),
                        rs.getString("reason"), rs.getString("decision_reason"),
                        rs.getTimestamp("created_at").toLocalDateTime()), id);
        if (rows.isEmpty() || (pending && !"PENDING".equals(rows.get(0).status()))) {
            throw new BusinessException("待处理申请不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> findView(Long id, long companyId) {
        ActionRecord action = requireAction(id, false);
        Target target = requireTarget(action.bizType(), action.bizId(), companyId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", action.id());
        view.put("contractId", action.contractId());
        view.put("bizType", action.bizType());
        view.put("bizId", action.bizId());
        view.put("actionType", action.actionType());
        view.put("actionText", actionLabel(action.actionType()));
        view.put("targetText", target.label());
        view.put("status", action.status());
        view.put("reason", action.reason());
        view.put("decisionReason", action.decisionReason());
        view.put("requesterCompanyId", action.requesterCompanyId());
        view.put("approverCompanyId", action.approverCompanyId());
        view.put("canReview", "PENDING".equals(action.status()) && action.approverCompanyId() == companyId);
        view.put("canCancel", "PENDING".equals(action.status())
                && action.requesterCompanyId() == companyId
                && action.requesterUserId() == AuthContext.userId());
        view.put("createdAt", action.createdAt());
        return view;
    }

    private void recordResult(ActionRecord action, long sourceCompanyId,
                              String resultStatus, String resultText, String reason) {
        if (approvalService == null) return;
        approvalService.recordResult(action.requesterCompanyId(), sourceCompanyId,
                "BILATERAL_ACTION", action.id(), action.contractId(), resultStatus,
                actionLabel(action.actionType()) + "申请" + resultText,
                "对方" + resultText + actionLabel(action.actionType()) + "申请", reason);
    }

    private void requirePermission(long companyId, String bizType) {
        if (CONTRACT.equals(bizType)) {
            accessControlService.requirePermission(companyId, "contract_sign");
        } else if (ATTACHMENT.equals(bizType)) {
            accessControlService.requireAnyPermission(companyId,
                    "contract_attachment_upload", "contract_sign", "order_create",
                    "reconciliation", "invoice_view");
        } else {
            accessControlService.requireAnyPermission(companyId,
                    "contract_sign", "order_create", "sales_order_receive", "inventory_receive");
        }
    }

    private boolean hasElectronicAbolishApproval(Long contractId) {
        if (contractState != null) {
            return count("""
                    SELECT COUNT(1) FROM bilateral_action_request
                    WHERE contract_id = ? AND biz_type = 'CONTRACT' AND action_type = 'VOID' AND status = 'APPROVED'
                    """, contractId) > 0 && contractState.electronicTaskCount(contractId) > 0;
        }
        return count("""
                SELECT COUNT(1)
                FROM bilateral_action_request action_request
                INNER JOIN fadada_contract_sign_task sign_task
                    ON sign_task.contract_id = action_request.contract_id
                   AND sign_task.sign_task_id IS NOT NULL
                WHERE action_request.contract_id = ?
                  AND action_request.biz_type = 'CONTRACT'
                  AND action_request.action_type = 'VOID'
                  AND action_request.status = 'APPROVED'
                """, contractId) > 0;
    }

    private long count(String sql, Long contractId) {
        if (attachmentState != null && sql.contains("FROM contract_attachment")) {
            return sql.contains("FOR UPDATE") ? attachmentState.unfinishedCount(contractId) : attachmentState.effectiveCount(contractId);
        }
        Long value = jdbc.queryForObject(sql, Long.class, contractId);
        return value == null ? 0 : value;
    }

    private String normalizeBizType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of(CONTRACT, ATTACHMENT, BUSINESS_DOCUMENT).contains(normalized)) {
            throw new BusinessException("业务类型不正确");
        }
        return normalized;
    }

    private String normalizeAction(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of(END, VOID, RESUME).contains(normalized)) throw new BusinessException("申请类型不正确");
        return normalized;
    }

    private String requireReason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.isBlank() || reason.length() > 500) {
            throw new BusinessException("请输入原因且不能超过 500 字");
        }
        return reason;
    }

    private String actionLabel(String actionType) {
        return END.equals(actionType) ? "结束" : (RESUME.equals(actionType) ? "恢复履约" : "作废");
    }

    private String attachmentLabel(String category) {
        return ContractAttachmentOperations.INVOICE.equals(category) ? "发票" : "转款凭证";
    }

    

    private record Target(String bizType, Long bizId, TradeContractRespDTO contract, String status,
                          String label, String sourceType) {
    }

    private record TargetRow(Long contractId, String status, String sourceType) {
    }

    private record ActionRecord(long id, long contractId, String bizType, long bizId,
                                String actionType, long requesterCompanyId, long requesterUserId,
                                long approverCompanyId, String status, String reason,
                                String decisionReason, LocalDateTime createdAt) {
    }
}
