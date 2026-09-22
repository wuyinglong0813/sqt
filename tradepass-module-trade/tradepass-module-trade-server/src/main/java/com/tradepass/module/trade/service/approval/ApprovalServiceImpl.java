package com.tradepass.module.trade.service.approval;

import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;
import com.tradepass.module.settlement.api.attachment.AttachmentApprovalOperations;
import com.tradepass.module.settlement.api.attachment.AttachmentApprovalOperations.*;
import com.tradepass.module.settlement.api.attachment.AttachmentStateOperations;
import com.tradepass.module.settlement.api.attachment.AttachmentStateOperations.*;
import com.tradepass.module.trade.api.approval.ApprovalOperations;
import com.tradepass.module.trade.api.approval.ApprovalOperations.*;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations;
import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations.*;

import com.tradepass.framework.mybatis.core.ApplicationIds;

import com.tradepass.framework.common.core.AuthContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ApprovalServiceImpl implements ApprovalService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.identity.api.directory.IdentityDirectoryOperations identityDirectory;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.contract.api.contract.ContractReader contractReader;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.settlement.api.attachment.AttachmentStateOperations attachmentState;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.settlement.api.attachment.AttachmentApprovalOperations attachmentApprovals;

    private final JdbcTemplate jdbc;
    private final AccessControlOperations accessControlService;

    public ApprovalServiceImpl(JdbcTemplate jdbc, AccessControlOperations accessControlService) {
        this.jdbc = jdbc;
        this.accessControlService = accessControlService;
    }

    public List<Map<String, Object>> pendingFulfillment() {
        long companyId = AuthContext.requireCompanyId();
        List<Map<String, Object>> items = new ArrayList<>();
        if (canReviewTradeDocuments(companyId)) {
            items.addAll(pendingSalesOrders(companyId));
        }
        if (canReviewAttachments(companyId)) {
            items.addAll(pendingAttachments(companyId));
        }
        items.addAll(pendingBilateralActions(companyId));
        items.sort(Comparator.comparing(
                item -> String.valueOf(item.getOrDefault("createdAt", "")),
                Comparator.reverseOrder()));
        return items;
    }

    public List<Map<String, Object>> results() {
        if (identityDirectory != null && contractReader != null) return ownedResults();
        long companyId = AuthContext.requireCompanyId();
        return jdbc.query("""
                        SELECT notification.id, notification.source_company_id,
                               company.name AS source_company_name,
                               notification.result_type, notification.source_id,
                               notification.contract_id, notification.result_status,
                               notification.title, notification.detail,
                               notification.rejected_reason, notification.read_at,
                               notification.created_at
                        FROM approval_result_notification notification
                        JOIN company ON company.id = notification.source_company_id
                        WHERE notification.recipient_company_id = ?
                        ORDER BY notification.created_at DESC, notification.id DESC
                        LIMIT 100
                        """, (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("sourceCompanyId", rs.getLong("source_company_id"));
                    item.put("sourceCompanyName", rs.getString("source_company_name"));
                    item.put("resultType", rs.getString("result_type"));
                    item.put("sourceId", rs.getLong("source_id"));
                    item.put("contractId", rs.getObject("contract_id", Long.class));
                    item.put("resultStatus", rs.getString("result_status"));
                    item.put("title", rs.getString("title"));
                    item.put("detail", rs.getString("detail"));
                    item.put("rejectedReason", rs.getString("rejected_reason"));
                    item.put("isRead", rs.getTimestamp("read_at") != null);
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    item.put("createdAt", createdAt == null ? null : createdAt.toLocalDateTime());
                    return item;
                }, companyId);
    }

    public Map<String, Object> summary() {
        long companyId = AuthContext.requireCompanyId();
        long pendingContracts = 0;
        if (accessControlService.hasPermission(companyId, "contract_sign")) {
            pendingContracts = contractReader.countContractsAwaitingSignature(companyId);
        }
        long pendingFulfillment = 0;
        if (canReviewTradeDocuments(companyId)) {
            pendingFulfillment += count("""
                    SELECT COUNT(1) FROM business_document
                    WHERE recipient_company_id = ? AND document_type IN ('SALES_ORDER', 'RETURN_ORDER')
                      AND status = 'ISSUED'
                      AND deleted_at IS NULL
                    """, companyId);
        }
        if (canReviewAttachments(companyId)) {
            pendingFulfillment += attachmentState != null ? attachmentState.pendingConfirmationCount(companyId) : count("""
                    SELECT COUNT(1) FROM contract_attachment
                    WHERE recipient_company_id = ? AND status = 'PENDING_CONFIRMATION'
                      AND category IN ('PAYMENT_VOUCHER', 'INVOICE')
                      AND deleted_at IS NULL
                    """, companyId);
        }
        pendingFulfillment += count("""
                SELECT COUNT(1) FROM bilateral_action_request
                WHERE approver_company_id = ? AND status = 'PENDING'
                """, companyId);
        long unreadResults = count("""
                SELECT COUNT(1) FROM approval_result_notification
                WHERE recipient_company_id = ? AND read_at IS NULL
                """, companyId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("pendingContractCount", pendingContracts);
        summary.put("pendingFulfillmentCount", pendingFulfillment);
        summary.put("pendingCount", pendingContracts + pendingFulfillment);
        summary.put("unreadResultCount", unreadResults);
        summary.put("hasMessage", pendingContracts + pendingFulfillment + unreadResults > 0);
        return summary;
    }

    @Transactional
    public void markResultRead(Long id) {
        long companyId = AuthContext.requireCompanyId();
        jdbc.update("""
                UPDATE approval_result_notification
                SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP)
                WHERE id = ? AND recipient_company_id = ?
                """, id, companyId);
    }

    @Transactional
    public void recordResult(long recipientCompanyId, long sourceCompanyId,
                             String resultType, long sourceId, Long contractId,
                             String resultStatus, String title, String detail,
                             String rejectedReason) {
        jdbc.update("""
                INSERT INTO approval_result_notification
                    (id, recipient_company_id, source_company_id, result_type, source_id,
                     contract_id, result_status, title, detail, rejected_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ApplicationIds.next(), recipientCompanyId, sourceCompanyId, resultType, sourceId,
                contractId, resultStatus, title, detail,
                rejectedReason == null || rejectedReason.isBlank() ? null : rejectedReason.trim());
    }

    private long count(String sql, long companyId) {
        Long value = jdbc.queryForObject(sql, Long.class, companyId);
        return value == null ? 0 : value;
    }

    private boolean canReviewAttachments(long companyId) {
        return accessControlService.hasPermission(companyId, "contract_attachment_upload")
                || accessControlService.hasPermission(companyId, "reconciliation")
                || accessControlService.hasPermission(companyId, "invoice_view");
    }

    private boolean canReviewTradeDocuments(long companyId) {
        return accessControlService.hasPermission(companyId, "sales_order_receive")
                || accessControlService.hasPermission(companyId, "contract_sign")
                || accessControlService.hasPermission(companyId, "order_create");
    }

    private List<Map<String, Object>> pendingSalesOrders(long companyId) {
        if (identityDirectory != null && contractReader != null) return ownedPendingSalesOrders(companyId);
        return jdbc.query("""
                        SELECT document.id, document.contract_id, document.document_no,
                               document.document_type,
                               document.created_at, document.company_id AS source_company_id,
                               company.name AS source_company_name,
                               contract.contract_no, contract.name AS contract_name
                        FROM business_document document
                        JOIN company ON company.id = document.company_id
                        JOIN trade_contract contract ON contract.id = document.contract_id
                        WHERE document.recipient_company_id = ?
                          AND document.document_type IN ('SALES_ORDER', 'RETURN_ORDER')
                          AND document.status = 'ISSUED'
                          AND document.deleted_at IS NULL
                        ORDER BY document.created_at DESC, document.id DESC
                        """, (rs, rowNum) -> item(
                        rs.getLong("id"), rs.getString("document_type"),
                        "RETURN_ORDER".equals(rs.getString("document_type")) ? "退货单" : "销售单",
                        rs.getLong("source_company_id"), rs.getString("source_company_name"),
                        rs.getLong("contract_id"), rs.getString("contract_no"),
                        rs.getString("contract_name"), rs.getString("document_no"),
                        null, null, rs.getTimestamp("created_at")), companyId);
    }

    private List<Map<String, Object>> pendingAttachments(long companyId) {
        if (attachmentApprovals != null) return attachmentApprovals.pendingAttachments(companyId).stream().map(attachment -> {
            Map<String, Object> view = new LinkedHashMap<>(attachment.view());
            // Keep the original LocalDateTime comparison, including equal-time stable ordering.
            view.put("createdAt", attachment.createdAt());
            return view;
        }).toList();
        return jdbc.query("""
                        SELECT attachment.id, attachment.contract_id, attachment.category,
                               attachment.original_name, attachment.content_type, attachment.voucher_date,
                               attachment.voucher_amount, attachment.invoice_date,
                               attachment.invoice_amount, attachment.file_size, attachment.created_at,
                               attachment.uploader_company_id AS source_company_id,
                               company.name AS source_company_name,
                               contract.contract_no, contract.name AS contract_name
                        FROM contract_attachment attachment
                        JOIN company ON company.id = attachment.uploader_company_id
                        JOIN trade_contract contract ON contract.id = attachment.contract_id
                        WHERE attachment.recipient_company_id = ?
                          AND attachment.status = 'PENDING_CONFIRMATION'
                          AND attachment.category IN ('PAYMENT_VOUCHER', 'INVOICE')
                          AND attachment.deleted_at IS NULL
                        ORDER BY attachment.created_at DESC, attachment.id DESC
                        """, (rs, rowNum) -> {
                    String category = rs.getString("category");
                    boolean invoice = ContractAttachmentOperations.INVOICE.equals(category);
                    LocalDate businessDate = rs.getObject(
                            invoice ? "invoice_date" : "voucher_date", LocalDate.class);
                    BigDecimal amount = rs.getBigDecimal(
                            invoice ? "invoice_amount" : "voucher_amount");
                    Map<String, Object> view = item(rs.getLong("id"), category,
                            invoice ? "发票" : "转款凭证",
                            rs.getLong("source_company_id"), rs.getString("source_company_name"),
                            rs.getLong("contract_id"), rs.getString("contract_no"),
                            rs.getString("contract_name"), rs.getString("original_name"),
                            businessDate, amount, rs.getTimestamp("created_at"));
                    String contentType = rs.getString("content_type");
                    view.put("contentType", contentType == null ? "" : contentType);
                    view.put("fileSize", rs.getLong("file_size"));
                    view.put("isImage", contentType != null && contentType.startsWith("image/"));
                    return view;
                }, companyId);
    }

    private List<Map<String, Object>> pendingBilateralActions(long companyId) {
        if (identityDirectory != null && contractReader != null) return ownedPendingBilateralActions(companyId);
        return jdbc.query("""
                        SELECT action.id, action.contract_id, action.biz_type, action.biz_id,
                               action.action_type, action.reason, action.created_at,
                               action.requester_company_id AS source_company_id,
                               company.name AS source_company_name,
                               contract.contract_no, contract.name AS contract_name,
                               attachment.category,
                               COALESCE(document.document_no, attachment.original_name,
                                        contract.contract_no) AS document_no
                        FROM bilateral_action_request action
                        JOIN company ON company.id = action.requester_company_id
                        JOIN trade_contract contract ON contract.id = action.contract_id
                        LEFT JOIN contract_attachment attachment
                          ON action.biz_type = 'ATTACHMENT' AND attachment.id = action.biz_id
                        LEFT JOIN business_document document
                          ON action.biz_type = 'BUSINESS_DOCUMENT' AND document.id = action.biz_id
                        WHERE action.approver_company_id = ? AND action.status = 'PENDING'
                        ORDER BY action.created_at DESC, action.id DESC
                        """, (rs, rowNum) -> {
                    String bizType = rs.getString("biz_type");
                    String actionType = rs.getString("action_type");
                    String category = rs.getString("category");
                    String targetText = "CONTRACT".equals(bizType) ? "合同"
                            : ("ATTACHMENT".equals(bizType)
                            ? (ContractAttachmentOperations.INVOICE.equals(category) ? "发票" : "转款凭证")
                            : "业务单据");
                    Map<String, Object> view = item(rs.getLong("id"), "BILATERAL_ACTION",
                            targetText + ("END".equals(actionType) ? "结束" : ("RESUME".equals(actionType) ? "恢复履约" : "作废")),
                            rs.getLong("source_company_id"), rs.getString("source_company_name"),
                            rs.getLong("contract_id"), rs.getString("contract_no"),
                            rs.getString("contract_name"), rs.getString("document_no"),
                            null, null, rs.getTimestamp("created_at"));
                    view.put("actionId", rs.getLong("id"));
                    view.put("bizType", bizType);
                    view.put("bizId", rs.getLong("biz_id"));
                    view.put("actionType", actionType);
                    view.put("reason", rs.getString("reason"));
                    return view;
                }, companyId);
    }

    private List<Map<String, Object>> ownedResults() {
        long companyId = AuthContext.requireCompanyId();
        return jdbc.query("""
                        SELECT notification.id, notification.source_company_id,
                               NULL AS source_company_name,
                               notification.result_type, notification.source_id,
                               notification.contract_id, notification.result_status,
                               notification.title, notification.detail,
                               notification.rejected_reason, notification.read_at,
                               notification.created_at
                        FROM approval_result_notification notification
                        WHERE notification.recipient_company_id = ?
                        ORDER BY notification.created_at DESC, notification.id DESC
                        """, (rs, rowNum) -> {
                    var names = identityDirectory.companyNames(List.of(rs.getLong("source_company_id")));
                    if (!names.containsKey(rs.getLong("source_company_id"))) return null;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("sourceCompanyId", rs.getLong("source_company_id"));
                    item.put("sourceCompanyName", names.get(rs.getLong("source_company_id")));
                    item.put("resultType", rs.getString("result_type"));
                    item.put("sourceId", rs.getLong("source_id"));
                    item.put("contractId", rs.getObject("contract_id", Long.class));
                    item.put("resultStatus", rs.getString("result_status"));
                    item.put("title", rs.getString("title"));
                    item.put("detail", rs.getString("detail"));
                    item.put("rejectedReason", rs.getString("rejected_reason"));
                    item.put("isRead", rs.getTimestamp("read_at") != null);
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    item.put("createdAt", createdAt == null ? null : createdAt.toLocalDateTime());
                    return item;
                }, companyId).stream().filter(java.util.Objects::nonNull).limit(100).toList();
    }

    private List<Map<String, Object>> ownedPendingSalesOrders(long companyId) {
        return jdbc.query("""
                        SELECT document.id, document.contract_id, document.document_no,
                               document.document_type,
                               document.created_at, document.company_id AS source_company_id,
                               NULL AS source_company_name,
                               NULL AS contract_no, NULL AS contract_name
                        FROM business_document document
                        WHERE document.recipient_company_id = ?
                          AND document.document_type IN ('SALES_ORDER', 'RETURN_ORDER')
                          AND document.status = 'ISSUED'
                          AND document.deleted_at IS NULL
                        ORDER BY document.created_at DESC, document.id DESC
                        """, (rs, rowNum) -> {
                    var contract = contractReader.selectById(rs.getLong("contract_id"));
                    var names = identityDirectory.companyNames(List.of(rs.getLong("source_company_id")));
                    if (contract == null || !names.containsKey(rs.getLong("source_company_id"))) return null;
                    return item(
                        rs.getLong("id"), rs.getString("document_type"),
                        "RETURN_ORDER".equals(rs.getString("document_type")) ? "退货单" : "销售单",
                        rs.getLong("source_company_id"), names.get(rs.getLong("source_company_id")),
                        rs.getLong("contract_id"), contract.getContractNo(),
                        contract.getName(), rs.getString("document_no"),
                        null, null, rs.getTimestamp("created_at"));
                }, companyId).stream().filter(java.util.Objects::nonNull).toList();
    }

    private List<Map<String, Object>> ownedPendingBilateralActions(long companyId) {
        return jdbc.query("""
                        SELECT action.id, action.contract_id, action.biz_type, action.biz_id,
                               action.action_type, action.reason, action.created_at,
                               action.requester_company_id AS source_company_id,
                               NULL AS source_company_name,
                               NULL AS contract_no, NULL AS contract_name,
                               NULL AS category,
                               document.document_no AS document_no
                        FROM bilateral_action_request action
                        LEFT JOIN business_document document
                          ON action.biz_type = 'BUSINESS_DOCUMENT' AND document.id = action.biz_id
                        WHERE action.approver_company_id = ? AND action.status = 'PENDING'
                        ORDER BY action.created_at DESC, action.id DESC
                        """, (rs, rowNum) -> {
                    var contract = contractReader.selectById(rs.getLong("contract_id"));
                    var names = identityDirectory.companyNames(List.of(rs.getLong("source_company_id")));
                    if (contract == null || !names.containsKey(rs.getLong("source_company_id"))) return null;
                    String bizType = rs.getString("biz_type");
                    var attachment = "ATTACHMENT".equals(bizType) ? attachmentState.state(rs.getLong("biz_id"), true) : null;
                    String documentNo = rs.getString("document_no");
                    if (documentNo == null && attachment != null) documentNo = attachment.originalName();
                    if (documentNo == null) documentNo = contract.getContractNo();
                    String actionType = rs.getString("action_type");
                    String category = attachment == null ? null : attachment.category();
                    String targetText = "CONTRACT".equals(bizType) ? "合同"
                            : ("ATTACHMENT".equals(bizType)
                            ? (ContractAttachmentOperations.INVOICE.equals(category) ? "发票" : "转款凭证")
                            : "业务单据");
                    Map<String, Object> view = item(rs.getLong("id"), "BILATERAL_ACTION",
                            targetText + ("END".equals(actionType) ? "结束" : ("RESUME".equals(actionType) ? "恢复履约" : "作废")),
                            rs.getLong("source_company_id"), names.get(rs.getLong("source_company_id")),
                            rs.getLong("contract_id"), contract.getContractNo(),
                            contract.getName(), documentNo,
                            null, null, rs.getTimestamp("created_at"));
                    view.put("actionId", rs.getLong("id"));
                    view.put("bizType", bizType);
                    view.put("bizId", rs.getLong("biz_id"));
                    view.put("actionType", actionType);
                    view.put("reason", rs.getString("reason"));
                    return view;
                }, companyId).stream().filter(java.util.Objects::nonNull).toList();
    }

    private Map<String, Object> item(long id, String approvalType, String typeText,
                                     long sourceCompanyId, String sourceCompanyName,
                                     long contractId, String contractNo, String contractName,
                                     String documentNo, LocalDate businessDate,
                                     BigDecimal amount, Timestamp createdAt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("approvalType", approvalType);
        item.put("typeText", typeText);
        item.put("sourceCompanyId", sourceCompanyId);
        item.put("sourceCompanyName", sourceCompanyName);
        item.put("contractId", contractId);
        item.put("contractNo", contractNo == null ? "" : contractNo);
        item.put("contractName", contractName == null ? "" : contractName);
        item.put("documentNo", documentNo == null ? "" : documentNo);
        item.put("businessDate", businessDate);
        item.put("amount", amount);
        LocalDateTime created = createdAt == null ? null : createdAt.toLocalDateTime();
        item.put("createdAt", created);
        return item;
    }
}
