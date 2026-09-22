package com.tradepass.module.trade.service.inventory;

import com.tradepass.module.trade.convert.document.BusinessDocumentConvert;
import com.tradepass.module.trade.service.approval.ApprovalService;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations.*;

import com.tradepass.framework.mybatis.core.ApplicationIds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentMapper;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class SalesOrderInventoryServiceImpl implements SalesOrderInventoryService {
    public static final String RECEIVE_ONLY = "RECEIVE_ONLY";
    public static final String INBOUND = "INBOUND";
    public static final String REJECT = "REJECT";

    private final JdbcTemplate jdbc;
    private final BusinessDocumentMapper documentMapper;
    private final ContractReader contractMapper;
    private final AccessControlOperations accessControlService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final ReconciliationAccountOperations reconciliationAccountService;
    private final SalesOrderSignatureService signatureService;
    private final UserIdentityOperations userIdentityService;
    private ApprovalService approvalService;

    @Autowired
    public SalesOrderInventoryServiceImpl(JdbcTemplate jdbc,
                                      BusinessDocumentMapper documentMapper,
                                      ContractReader contractMapper,
                                      AccessControlOperations accessControlService,
                                      AuditLogService auditLogService,
                                      ObjectMapper objectMapper,
                                      ReconciliationAccountOperations reconciliationAccountService,
                                      SalesOrderSignatureService signatureService,
                                      UserIdentityOperations userIdentityService) {
        this.jdbc = jdbc;
        this.documentMapper = documentMapper;
        this.contractMapper = contractMapper;
        this.accessControlService = accessControlService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.reconciliationAccountService = reconciliationAccountService;
        this.signatureService = signatureService;
        this.userIdentityService = userIdentityService;
    }

    @Autowired
    public void setApprovalService(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    SalesOrderInventoryServiceImpl(JdbcTemplate jdbc,
                               BusinessDocumentMapper documentMapper,
                               ContractReader contractMapper,
                               AccessControlOperations accessControlService,
                               AuditLogService auditLogService,
                               ObjectMapper objectMapper) {
        this(jdbc, documentMapper, contractMapper, accessControlService, auditLogService,
                objectMapper, null, null, null);
    }

    @Transactional
    public void saveDocumentItems(BusinessDocumentDO document) {
        if (document == null || document.getId() == null || document.getRecipientCompanyId() == null) return;
        List<SalesItem> items = parseItems(document.getContent());
        if (items.isEmpty()) throw new BusinessException("销售单至少需要一项有效商品");
        jdbc.update("DELETE FROM business_document_item WHERE document_id = ?", document.getId());
        for (SalesItem item : items) {
            jdbc.update("""
                    INSERT INTO business_document_item
                    (id, document_id, issuer_company_id, recipient_company_id, line_no, line_type, product_name,
                     specification, base_unit, quantity, unit_price, amount, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, ApplicationIds.next(), document.getId(), document.getCompanyId(), document.getRecipientCompanyId(),
                    item.lineNo(), item.lineType(), item.productName(), item.specification(), item.baseUnit(),
                    item.quantity(), item.unitPrice(), item.amount(), item.remark());
        }
    }

    public void selectReturnWarehouse(BusinessDocumentDO document, long companyId, Long warehouseId) {
        if (document == null || !"RETURN_ORDER".equals(document.getDocumentType())) return;
        requireWarehouse(companyId, warehouseId,
                Long.valueOf(companyId).equals(document.getBuyerCompanyId())
                        ? "请选择退货出库仓库" : "请选择退货入库仓库");
        if (Long.valueOf(companyId).equals(document.getBuyerCompanyId())) {
            document.setOutboundWarehouseId(warehouseId);
        } else if (Long.valueOf(companyId).equals(document.getSupplierCompanyId())) {
            document.setInboundWarehouseId(warehouseId);
        } else {
            throw new BusinessException("当前企业不是退货单供需方");
        }
    }

    public Map<String, Object> documentDetail(Long documentId) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId,
                "contract_view", "contract_sign", "order_create", "sales_order_receive", "inventory_view");
        BusinessDocumentDO document = requireDocumentParty(documentId, companyId);
        boolean recipient = Long.valueOf(companyId).equals(document.getRecipientCompanyId());
        boolean owner = Long.valueOf(companyId).equals(document.getCompanyId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", document.getId());
        view.put("contractId", document.getContractId());
        view.put("issuerCompanyId", document.getCompanyId());
        view.put("recipientCompanyId", document.getRecipientCompanyId());
        view.put("documentNo", document.getDocumentNo());
        view.put("documentType", document.getDocumentType());
        view.put("typeLabel", documentLabel(document));
        view.put("templateName", document.getTemplateName());
        view.put("sourceType", document.getSourceType());
        view.put("status", document.getStatus());
        view.put("statusText", "ISSUED".equals(document.getStatus()) && recipient
                ? "待我方确认" : statusText(document.getStatus()));
        view.put("createdAt", document.getCreatedAt());
        view.put("acknowledgedAt", document.getAcknowledgedAt());
        view.put("rejectedReason", document.getRejectedReason() == null ? "" : document.getRejectedReason());
        try {
            view.put("content", objectMapper.readTree(document.getContent()));
        } catch (Exception exception) {
            view.put("content", Map.of());
        }
        view.put("items", documentItems(documentId));
        TradeContractRespDTO contract = contractMapper.selectById(document.getContractId());
        boolean canReceivePermission = accessControlService.hasPermission(companyId, "sales_order_receive")
                || ("RETURN_ORDER".equals(document.getDocumentType())
                && (accessControlService.hasPermission(companyId, "contract_sign")
                || accessControlService.hasPermission(companyId, "order_create")));
        Long supplierCompanyId = supplierCompanyId(document, contract);
        Long buyerCompanyId = buyerCompanyId(document, contract);
        view.put("supplierCompanyId", supplierCompanyId);
        view.put("buyerCompanyId", buyerCompanyId);
        view.put("viewerCompanyId", companyId);
        view.put("outboundWarehouseId", document.getOutboundWarehouseId());
        view.put("inboundWarehouseId", document.getInboundWarehouseId());
        boolean canReceive = recipient && canReceivePermission;
        boolean contractReadOnly = contract == null || contractLocked(contract);
        boolean inboundOwner = "RETURN_ORDER".equals(document.getDocumentType())
                ? Long.valueOf(companyId).equals(supplierCompanyId) : recipient;
        boolean canInbound = inboundOwner && accessControlService.hasPermission(companyId, "inventory_receive");
        view.put("canReceive", !contractReadOnly && canReceive && "ISSUED".equals(document.getStatus()));
        view.put("canReject", !contractReadOnly && canReceive && "ISSUED".equals(document.getStatus()));
        view.put("canInbound", !contractReadOnly && canInbound && ("ACKNOWLEDGED".equals(document.getStatus())
                || (recipient && "ISSUED".equals(document.getStatus()))));
        view.put("contractReadOnly", contractReadOnly);
        view.put("contractStatus", contract == null ? "" : contract.getStatus());
        boolean editable = "DRAFT".equals(document.getStatus()) || "REJECTED".equals(document.getStatus());
        view.put("canEditDraft", !contractReadOnly && owner && editable && contract != null
                && ("PENDING".equals(contract.getStatus()) || "ACTIVE".equals(contract.getStatus())));
        view.put("canPublish", !contractReadOnly && owner && editable && contract != null
                && "ACTIVE".equals(contract.getStatus()));
        boolean creator = owner && Long.valueOf(AuthContext.userId()).equals(document.getCreatedBy());
        view.put("canDeleteDraft", !contractReadOnly && creator && ("DRAFT".equals(document.getStatus())
                || "REJECTED".equals(document.getStatus())));
        view.put("canWithdraw", !contractReadOnly && creator && "ISSUED".equals(document.getStatus()));
        view.put("canRequestVoid", !contractReadOnly
                && List.of("ACKNOWLEDGED", "INBOUNDED").contains(document.getStatus()));
        return view;
    }

    public List<Map<String, Object>> listWarehouses() {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId,
                "inventory_view", "inventory_receive", "sales_order_receive",
                "contract_sign", "order_create");
        return jdbc.query("""
                        SELECT id, name, address, enabled, created_at
                        FROM warehouse WHERE company_id = ? AND enabled = 1
                        ORDER BY created_at, id
                        """, (rs, rowNum) -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", rs.getLong("id"));
                    view.put("name", rs.getString("name"));
                    view.put("address", rs.getString("address"));
                    view.put("enabled", rs.getBoolean("enabled"));
                    view.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    return view;
                }, companyId);
    }

    @Transactional
    public Map<String, Object> createWarehouse(String name, String address) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "inventory_receive");
        String safeName = name == null ? "" : name.trim();
        String safeAddress = address == null ? "" : address.trim();
        if (safeName.isBlank() || safeName.length() > 128) throw new BusinessException("仓库名称不能为空且不能超过 128 字");
        if (safeAddress.length() > 256) throw new BusinessException("仓库地址不能超过 256 字");
        Long id = ApplicationIds.next();
        try {
            jdbc.update("""
                    INSERT INTO warehouse (id, company_id, name, address, created_by)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, companyId, safeName, safeAddress, AuthContext.userId());
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("仓库名称已存在");
        }
        auditLogService.log(companyId, "WAREHOUSE", id, "CREATE", "创建仓库 " + safeName);
        return listWarehouses().stream().filter(item -> id != null && id.equals(item.get("id")))
                .findFirst().orElseThrow(() -> new BusinessException("仓库创建失败"));
    }

    public Map<String, Object> inventoryOverview() {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "inventory_view");
        List<Map<String, Object>> balances = jdbc.query("""
                        SELECT balance.id, warehouse.id AS warehouse_id, warehouse.name AS warehouse_name,
                               product.id AS product_id, product.product_name, product.specification,
                               product.base_unit, balance.quantity, balance.unit_price,
                               balance.inventory_amount, balance.updated_at
                        FROM inventory_balance balance
                        JOIN warehouse ON warehouse.id = balance.warehouse_id
                        JOIN inventory_product product ON product.id = balance.product_id
                        WHERE balance.company_id = ? AND balance.quantity <> 0
                        ORDER BY warehouse.name, product.product_name, product.specification
                        """, (rs, rowNum) -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", rs.getLong("id"));
                    view.put("warehouseId", rs.getLong("warehouse_id"));
                    view.put("warehouseName", rs.getString("warehouse_name"));
                    view.put("productId", rs.getLong("product_id"));
                    view.put("productName", rs.getString("product_name"));
                    view.put("specification", rs.getString("specification"));
                    view.put("baseUnit", rs.getString("base_unit"));
                    view.put("quantity", rs.getBigDecimal("quantity"));
                    view.put("unitPrice", rs.getBigDecimal("unit_price"));
                    view.put("inventoryAmount", rs.getBigDecimal("inventory_amount"));
                    view.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime());
                    return view;
                }, companyId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("warehouseCount", jdbc.queryForObject(
                "SELECT COUNT(1) FROM warehouse WHERE company_id = ? AND enabled = 1", Long.class, companyId));
        result.put("productCount", balances.size());
        result.put("balances", balances);
        result.put("canManualInbound", accessControlService.hasPermission(companyId, "inventory_receive"));
        return result;
    }

    @Transactional
    public Map<String, Object> manualInbound(Long warehouseId, String requestId, String name,
            String specification, String unit, BigDecimal quantity, BigDecimal price, String remark) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "inventory_receive");
        name = safeSpec(name); specification = safeSpec(specification); unit = safeSpec(unit);
        remark = safeSpec(remark);
        if (requestId == null || !requestId.matches("[a-zA-Z0-9-]{16,64}"))
            throw new BusinessException("入库请求标识无效，请重新打开入库表单");
        if (name.isBlank() || name.length() > 128 || unit.isBlank() || unit.length() > 32
                || specification.length() > 256 || remark.length() > 500)
            throw new BusinessException("请填写商品名称、单位，并检查内容长度");
        if (quantity == null || quantity.signum() <= 0 || quantity.scale() > 4
                || quantity.compareTo(new BigDecimal("99999999999999")) > 0
                || price == null || price.signum() < 0 || price.scale() > 4
                || price.compareTo(new BigDecimal("99999999999999")) > 0)
            throw new BusinessException("数量须大于零，单价不能为负，最多四位小数");
        BigDecimal amount = quantity.multiply(price).setScale(2, RoundingMode.HALF_UP);
        if (amount.precision() > 18) throw new BusinessException("入库金额过大");
        requireWarehouse(companyId, warehouseId);
        // Serialize submissions to this warehouse, including retries of the same request.
        jdbc.queryForObject("SELECT id FROM warehouse WHERE id = ? AND company_id = ? FOR UPDATE",
                Long.class, warehouseId, companyId);
        List<Long> existing = jdbc.query("SELECT id FROM inventory_manual_entry WHERE company_id = ? AND request_id = ?",
                (rs, row) -> rs.getLong(1), companyId, requestId);
        if (!existing.isEmpty()) return Map.of("id", existing.get(0));
        Long productId = findOrCreateProduct(companyId, name, specification, unit);
        Long entryId = ApplicationIds.next();
        jdbc.update("""
                INSERT INTO inventory_manual_entry
                (id, company_id, request_id, warehouse_id, product_id, quantity, unit_price, amount, remark, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entryId, companyId, requestId, warehouseId, productId, quantity, price, amount, remark, AuthContext.userId());
        jdbc.update("""
                INSERT INTO inventory_balance
                (id, company_id, warehouse_id, product_id, quantity, unit_price, inventory_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    unit_price = (inventory_amount + VALUES(inventory_amount)) / (quantity + VALUES(quantity)),
                    inventory_amount = inventory_amount + VALUES(inventory_amount),
                    quantity = quantity + VALUES(quantity), updated_at = CURRENT_TIMESTAMP
                """, ApplicationIds.next(), companyId, warehouseId, productId, quantity, price, amount);
        BigDecimal balance = jdbc.queryForObject("""
                SELECT quantity FROM inventory_balance WHERE company_id = ? AND warehouse_id = ? AND product_id = ?
                """, BigDecimal.class, companyId, warehouseId, productId);
        jdbc.update("""
                INSERT INTO inventory_transaction
                (id, company_id, warehouse_id, product_id, biz_type, biz_id, quantity_delta, balance_after, created_by)
                VALUES (?, ?, ?, ?, 'MANUAL_INBOUND', ?, ?, ?, ?)
                """, ApplicationIds.next(), companyId, warehouseId, productId, entryId, quantity, balance, AuthContext.userId());
        return Map.of("id", entryId);
    }

    public List<Map<String, Object>> searchProducts(String keyword, Integer limit) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId,
                "contract_sign", "order_create", "inventory_view", "inventory_receive");
        String safeKeyword = keyword == null ? "" : keyword.trim();
        int safeLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 200));
        String pattern = "%" + safeKeyword.replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("""
                        SELECT product.id, product.product_name, product.specification,
                               product.base_unit, COALESCE(SUM(balance.quantity), 0) AS quantity
                        FROM inventory_product product
                        LEFT JOIN inventory_balance balance
                          ON balance.product_id = product.id AND balance.company_id = product.company_id
                        WHERE product.company_id = ?
                          AND (? = '' OR product.product_name LIKE ? ESCAPE '\\\\'
                               OR product.specification LIKE ? ESCAPE '\\\\')
                        GROUP BY product.id, product.product_name, product.specification, product.base_unit
                        ORDER BY product.product_name, product.specification, product.id
                        LIMIT ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("productName", rs.getString("product_name"));
                    item.put("specification", rs.getString("specification"));
                    item.put("baseUnit", rs.getString("base_unit"));
                    item.put("quantity", rs.getBigDecimal("quantity"));
                    return item;
                }, companyId, safeKeyword, pattern, pattern, safeLimit);
    }

    @Transactional
    public Map<String, Object> receive(Long documentId, String decision, Long warehouseId) {
        return receive(documentId, decision, warehouseId, null, null, null);
    }

    @Transactional
    public Map<String, Object> receive(Long documentId, String decision, Long warehouseId,
                                       String rejectedReason) {
        return receive(documentId, decision, warehouseId, rejectedReason, null, null);
    }

    @Transactional
    public Map<String, Object> receive(Long documentId, String decision, Long warehouseId,
                                       String rejectedReason, String signatureName,
                                       byte[] signatureData) {
        long companyId = AuthContext.requireCompanyId();
        BusinessDocumentDO document = documentMapper.selectByIdForUpdate(documentId);
        if (document != null && "RETURN_ORDER".equals(document.getDocumentType())) {
            accessControlService.requireAnyPermission(companyId,
                    "sales_order_receive", "contract_sign", "order_create");
        } else {
            accessControlService.requirePermission(companyId, "sales_order_receive");
        }
        String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!RECEIVE_ONLY.equals(normalized) && !INBOUND.equals(normalized) && !REJECT.equals(normalized)) {
            throw new BusinessException("接收方式不正确");
        }
        TradeContractRespDTO contract = document == null ? null : contractMapper.selectById(document.getContractId());
        if (contract != null && contractLocked(contract)) {
            throw new BusinessException("合同正在等待处理、已结束或已作废，单据仅允许查看");
        }
        boolean recipient = document != null
                && Long.valueOf(companyId).equals(document.getRecipientCompanyId());
        boolean supplierCompletingReturnInbound = document != null
                && "RETURN_ORDER".equals(document.getDocumentType())
                && INBOUND.equals(normalized)
                && "ACKNOWLEDGED".equals(document.getStatus())
                && Long.valueOf(companyId).equals(supplierCompanyId(document, contract));
        if (document == null || document.getDeletedAt() != null || !isConfirmableDocument(document)
                || (!recipient && !supplierCompletingReturnInbound)) {
            throw new BusinessException(document != null && "RETURN_ORDER".equals(document.getDocumentType())
                    ? "待确认退货单不存在" : "待接收销售单不存在");
        }
        String documentLabel = documentLabel(document);
        if (REJECT.equals(normalized)) {
            if (!"ISSUED".equals(document.getStatus())) {
                throw new BusinessException(documentLabel + "当前状态不能驳回");
            }
            String reason = rejectedReason == null ? "" : rejectedReason.trim();
            if (reason.isBlank() || reason.length() > 500) {
                throw new BusinessException("请输入驳回原因且不能超过 500 字");
            }
            document.setStatus("REJECTED");
            document.setRejectedReason(reason);
            documentMapper.updateById(document);
            auditLogService.log(companyId, "BUSINESS_DOCUMENT_RECEIPT", documentId,
                    "REJECT", "驳回" + documentLabel + " " + document.getDocumentNo() + "：" + reason);
            recordDocumentResult(document, companyId, "REJECTED",
                    documentLabel + "已被驳回", "对方已驳回" + documentLabel + " " + document.getDocumentNo(), reason);
            return documentDetail(documentId);
        }
        if ("INBOUNDED".equals(document.getStatus())) return documentDetail(documentId);
        if (!"ISSUED".equals(document.getStatus()) && !"ACKNOWLEDGED".equals(document.getStatus())) {
            throw new BusinessException("RETURN_ORDER".equals(document.getDocumentType())
                    ? "退货单尚未发布，不能确认或入库"
                    : "销售单尚未发布，不能接收或入库");
        }
        if (RECEIVE_ONLY.equals(normalized) && "ACKNOWLEDGED".equals(document.getStatus())) {
            return documentDetail(documentId);
        }

        boolean requiresSignature = "ISSUED".equals(document.getStatus());
        String signerName = null;
        if (requiresSignature) {
            if (signatureData == null || signatureData.length == 0) {
                throw new BusinessException("请先完成手写签名");
            }
            signerName = userIdentityService == null
                    ? "用户" + AuthContext.userId()
                    : userIdentityService.currentDisplayName();
            if (signerName == null || signerName.isBlank()) signerName = "用户" + AuthContext.userId();
        }
        boolean returnOrder = "RETURN_ORDER".equals(document.getDocumentType());
        Long supplierCompanyId = supplierCompanyId(document, contract);
        Long buyerCompanyId = buyerCompanyId(document, contract);
        if (returnOrder && !REJECT.equals(normalized)) {
            if (Long.valueOf(companyId).equals(buyerCompanyId)) {
                requireWarehouse(companyId, warehouseId, "请选择退货出库仓库");
                document.setOutboundWarehouseId(warehouseId);
            } else if (Long.valueOf(companyId).equals(supplierCompanyId) && INBOUND.equals(normalized)) {
                requireWarehouse(companyId, warehouseId, "请选择退货入库仓库");
                document.setInboundWarehouseId(warehouseId);
            }
            documentMapper.updateById(document);
        }
        if (INBOUND.equals(normalized)) {
            accessControlService.requirePermission(companyId, "inventory_receive");
            requireWarehouse(companyId, warehouseId,
                    returnOrder ? "请选择退货入库仓库" : "请选择入库仓库");
        }

        Long receiptId = existingReceipt(companyId, documentId);
        if (receiptId == null) {
            receiptId = ApplicationIds.next();
            jdbc.update("""
                    INSERT INTO sales_order_receipt
                    (id, company_id, document_id, decision, status, received_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, receiptId, companyId, documentId, normalized,
                    INBOUND.equals(normalized) ? "INBOUNDING" : "RECEIVED_PENDING_INBOUND",
                    AuthContext.userId());
        } else {
            jdbc.update("UPDATE sales_order_receipt SET decision = ?, status = ? WHERE id = ?",
                    normalized, INBOUND.equals(normalized) ? "INBOUNDING" : "RECEIVED_PENDING_INBOUND", receiptId);
        }
        if (requiresSignature) {
            if (signatureService == null) {
                throw new BusinessException("单据签名服务尚未启用");
            }
            signatureService.save(companyId, documentId, receiptId, signerName,
                    signatureName, signatureData);
        }

        if (RECEIVE_ONLY.equals(normalized)) {
            document.setStatus("ACKNOWLEDGED");
            document.setAcknowledgedBy(AuthContext.userId());
            document.setAcknowledgedAt(LocalDateTime.now());
            documentMapper.updateById(document);
            recordReconciliation(document);
            auditLogService.log(companyId, "BUSINESS_DOCUMENT_RECEIPT", receiptId,
                    "RECEIVE", "确认" + documentLabel + " " + document.getDocumentNo() + "，暂不入库");
            recordDocumentResult(document, companyId, "APPROVED",
                    documentLabel + "已确认", "对方已确认" + documentLabel + " " + document.getDocumentNo(), null);
            return documentDetail(documentId);
        }

        Long existingInbound = jdbc.query("""
                        SELECT id FROM inventory_inbound
                        WHERE company_id = ? AND source_document_id = ? LIMIT 1
                        """, rs -> rs.next() ? rs.getLong(1) : null, companyId, documentId);
        if (existingInbound != null) {
            document.setStatus("INBOUNDED");
            documentMapper.updateById(document);
            recordReconciliation(document);
            recordDocumentResult(document, companyId, "INBOUNDED",
                    documentLabel + "已确认并入库", "对方已确认并入库" + documentLabel + " " + document.getDocumentNo(), null);
            return documentDetail(documentId);
        }

        List<Map<String, Object>> items = documentItems(documentId).stream()
                .filter(item -> !"FEE".equals(item.get("lineType")))
                .toList();
        if (items.isEmpty()) throw new BusinessException(documentLabel + "没有可入库商品");
        Long transferId = returnOrder
                ? createReturnTransfer(document, items, AuthContext.userId()) : null;

        String inboundNo = createInboundNo();
        Long inboundId = ApplicationIds.next();
        jdbc.update("""
                INSERT INTO inventory_inbound
                (id, company_id, warehouse_id, source_document_id, inbound_no, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, inboundId, companyId, warehouseId, documentId, inboundNo, AuthContext.userId());
        for (Map<String, Object> item : items) {
            BigDecimal quantity = (BigDecimal) item.get("quantity");
            if (quantity == null || quantity.signum() <= 0) throw new BusinessException("销售单商品数量必须大于 0");
            BigDecimal unitPrice = (BigDecimal) item.get("unitPrice");
            if (unitPrice == null || unitPrice.signum() < 0) throw new BusinessException("销售单商品单价不能小于 0");
            BigDecimal inboundAmount = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
            Long productId = findOrCreateProduct(companyId,
                    String.valueOf(item.get("productName")), String.valueOf(item.get("specification")),
                    String.valueOf(item.get("baseUnit")));
            jdbc.update("""
                    INSERT INTO inventory_balance
                    (id, company_id, warehouse_id, product_id, quantity, unit_price, inventory_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        unit_price = CASE
                            WHEN quantity + VALUES(quantity) = 0 THEN 0
                            ELSE (inventory_amount + VALUES(inventory_amount))
                                / (quantity + VALUES(quantity))
                        END,
                        inventory_amount = inventory_amount + VALUES(inventory_amount),
                        quantity = quantity + VALUES(quantity),
                        updated_at = CURRENT_TIMESTAMP
                    """, ApplicationIds.next(), companyId, warehouseId, productId, quantity, unitPrice, inboundAmount);
            BigDecimal balance = jdbc.queryForObject("""
                    SELECT quantity FROM inventory_balance
                    WHERE company_id = ? AND warehouse_id = ? AND product_id = ?
                    """, BigDecimal.class, companyId, warehouseId, productId);
            jdbc.update("""
                    INSERT INTO inventory_inbound_item
                    (id, inbound_id, document_item_id, product_id, quantity, unit_price, amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, ApplicationIds.next(), inboundId, item.get("id"), productId, quantity, unitPrice, inboundAmount);
            jdbc.update("""
                    INSERT INTO inventory_transaction
                    (id, company_id, warehouse_id, product_id, biz_type, biz_id,
                     quantity_delta, balance_after, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, ApplicationIds.next(), companyId, warehouseId, productId,
                    "RETURN_ORDER".equals(document.getDocumentType())
                            ? "RETURN_ORDER_INBOUND" : "SALES_ORDER_INBOUND",
                    returnOrder ? transferId : inboundId, quantity, balance, AuthContext.userId());
        }
        jdbc.update("UPDATE sales_order_receipt SET decision = 'INBOUND', status = 'INBOUNDED' WHERE id = ?", receiptId);
        document.setStatus("INBOUNDED");
        if (document.getAcknowledgedAt() == null) {
            document.setAcknowledgedBy(AuthContext.userId());
            document.setAcknowledgedAt(LocalDateTime.now());
        }
        documentMapper.updateById(document);
        recordReconciliation(document);
        auditLogService.log(companyId, "INVENTORY_INBOUND", inboundId,
                "CREATE", documentLabel + " " + document.getDocumentNo() + " 确认并入库至仓库 " + warehouseId);
        recordDocumentResult(document, companyId, "INBOUNDED",
                documentLabel + "已确认并入库", "对方已确认并入库" + documentLabel + " " + document.getDocumentNo(), null);
        return documentDetail(documentId);
    }

    private void recordDocumentResult(BusinessDocumentDO document, long sourceCompanyId,
                                      String resultStatus, String title, String detail,
                                      String rejectedReason) {
        if (approvalService == null || document.getCompanyId() == null || document.getId() == null
                || document.getCompanyId() == sourceCompanyId) return;
        approvalService.recordResult(document.getCompanyId(), sourceCompanyId,
                document.getDocumentType(), document.getId(), document.getContractId(), resultStatus,
                title, detail, rejectedReason);
    }

    @Transactional
    public void reverseDocumentInventory(Long documentId, long actionRequestId, long userId) {
        BusinessDocumentDO document = documentMapper.selectByIdForUpdate(documentId);
        if (document == null || !"INBOUNDED".equals(document.getStatus())) return;
        if ("RETURN_ORDER".equals(document.getDocumentType())) {
            reverseReturnTransfer(documentId, actionRequestId, userId);
        } else {
            reverseSalesInbound(documentId, actionRequestId, userId);
        }
    }

    private void reverseReturnTransfer(Long documentId, long actionRequestId, long userId) {
        List<TransferRecord> transfers = jdbc.query("""
                        SELECT id, outbound_company_id, outbound_warehouse_id,
                               inbound_company_id, inbound_warehouse_id, status
                        FROM inventory_transfer WHERE source_document_id = ? FOR UPDATE
                        """, (rs, rowNum) -> new TransferRecord(
                        rs.getLong("id"), rs.getLong("outbound_company_id"),
                        rs.getLong("outbound_warehouse_id"), rs.getLong("inbound_company_id"),
                        rs.getLong("inbound_warehouse_id"), rs.getString("status")), documentId);
        if (transfers.isEmpty() || "REVERSED".equals(transfers.get(0).status())) return;
        TransferRecord transfer = transfers.get(0);
        List<InventoryMovement> inbound = originalMovements(
                transfer.inboundCompanyId(), "RETURN_ORDER_INBOUND", transfer.id());
        List<InventoryMovement> outbound = originalMovements(
                transfer.outboundCompanyId(), "RETURN_ORDER_OUTBOUND", transfer.id());
        validateDecreaseMovements(inbound);
        for (InventoryMovement movement : inbound) {
            applyMovement(movement, movement.quantity().negate(),
                    "RETURN_ORDER_INBOUND_VOID", actionRequestId, userId);
        }
        for (InventoryMovement movement : outbound) {
            applyMovement(movement, movement.quantity().abs(),
                    "RETURN_ORDER_OUTBOUND_VOID", actionRequestId, userId);
        }
        jdbc.update("""
                UPDATE inventory_transfer
                SET status = 'REVERSED', reversed_by_action_id = ?, reversed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'COMPLETED'
                """, actionRequestId, transfer.id());
    }

    private void reverseSalesInbound(Long documentId, long actionRequestId, long userId) {
        List<InboundRecord> inbounds = jdbc.query("""
                        SELECT id, company_id, warehouse_id, status
                        FROM inventory_inbound WHERE source_document_id = ? FOR UPDATE
                        """, (rs, rowNum) -> new InboundRecord(
                        rs.getLong("id"), rs.getLong("company_id"),
                        rs.getLong("warehouse_id"), rs.getString("status")), documentId);
        if (inbounds.isEmpty() || "REVERSED".equals(inbounds.get(0).status())) return;
        InboundRecord inbound = inbounds.get(0);
        List<InventoryMovement> movements = originalMovements(
                inbound.companyId(), "SALES_ORDER_INBOUND", inbound.id());
        validateDecreaseMovements(movements);
        for (InventoryMovement movement : movements) {
            applyMovement(movement, movement.quantity().negate(),
                    "SALES_ORDER_INBOUND_VOID", actionRequestId, userId);
        }
        jdbc.update("UPDATE inventory_inbound SET status = 'REVERSED' WHERE id = ?", inbound.id());
    }

    private List<InventoryMovement> originalMovements(long companyId, String bizType, long bizId) {
        return jdbc.query("""
                        SELECT company_id, warehouse_id, product_id, quantity_delta
                        FROM inventory_transaction
                        WHERE company_id = ? AND biz_type = ? AND biz_id = ?
                        ORDER BY id
                        """, (rs, rowNum) -> new InventoryMovement(
                        rs.getLong("company_id"), rs.getLong("warehouse_id"),
                        rs.getLong("product_id"), rs.getBigDecimal("quantity_delta").abs()),
                companyId, bizType, bizId);
    }

    private void validateDecreaseMovements(List<InventoryMovement> movements) {
        for (InventoryMovement movement : movements) {
            BalanceSnapshot balance = lockBalance(movement);
            if (balance.quantity().compareTo(movement.quantity()) < 0) {
                throw new BusinessException("当前库存不足，不能作废已入库单据；请先处理后续出库记录");
            }
        }
    }

    private void applyMovement(InventoryMovement movement, BigDecimal delta,
                               String bizType, long bizId, long userId) {
        BalanceSnapshot balance = lockBalance(movement);
        BigDecimal nextQuantity = balance.quantity().add(delta);
        if (nextQuantity.signum() < 0) throw new BusinessException("库存不足，不能完成作废冲销");
        BigDecimal unitCost = balance.quantity().signum() == 0
                ? balance.unitPrice() : balance.amount().divide(balance.quantity(), 6, RoundingMode.HALF_UP);
        BigDecimal nextAmount = balance.amount().add(
                unitCost.multiply(delta).setScale(2, RoundingMode.HALF_UP));
        if (nextQuantity.signum() == 0) nextAmount = BigDecimal.ZERO.setScale(2);
        jdbc.update("""
                UPDATE inventory_balance
                SET quantity = ?, inventory_amount = ?, unit_price = ?, updated_at = CURRENT_TIMESTAMP
                WHERE company_id = ? AND warehouse_id = ? AND product_id = ?
                """, nextQuantity, nextAmount,
                nextQuantity.signum() == 0 ? BigDecimal.ZERO : unitCost,
                movement.companyId(), movement.warehouseId(), movement.productId());
        jdbc.update("""
                INSERT INTO inventory_transaction
                (id, company_id, warehouse_id, product_id, biz_type, biz_id,
                 quantity_delta, balance_after, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ApplicationIds.next(), movement.companyId(), movement.warehouseId(), movement.productId(),
                bizType, bizId, delta, nextQuantity, userId);
    }

    private BalanceSnapshot lockBalance(InventoryMovement movement) {
        List<BalanceSnapshot> rows = jdbc.query("""
                        SELECT quantity, unit_price, inventory_amount
                        FROM inventory_balance
                        WHERE company_id = ? AND warehouse_id = ? AND product_id = ?
                        FOR UPDATE
                        """, (rs, rowNum) -> new BalanceSnapshot(
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("inventory_amount")),
                movement.companyId(), movement.warehouseId(), movement.productId());
        if (rows.isEmpty()) throw new BusinessException("库存记录不存在，不能完成作废冲销");
        return rows.get(0);
    }

    private BusinessDocumentDO requireDocumentParty(Long documentId, long companyId) {
        BusinessDocumentDO document = documentMapper.selectById(documentId);
        if (document == null || document.getDeletedAt() != null || !isConfirmableDocument(document)) {
            throw new BusinessException("销售单不存在");
        }
        TradeContractRespDTO contract = contractMapper.selectById(document.getContractId());
        if (contract == null || (!Long.valueOf(companyId).equals(contract.getCompanyId())
                && !Long.valueOf(companyId).equals(contract.getCounterpartyCompanyId()))) {
            throw new BusinessException("销售单不存在");
        }
        if ("DRAFT".equals(document.getStatus())
                && !Long.valueOf(companyId).equals(document.getCompanyId())) {
            throw new BusinessException("销售单不存在");
        }
        return document;
    }

    private List<Map<String, Object>> documentItems(Long documentId) {
        return jdbc.query("""
                        SELECT id, line_no, line_type, product_name, specification, base_unit,
                               quantity, unit_price, amount, remark
                        FROM business_document_item WHERE document_id = ? ORDER BY line_no
                        """, (rs, rowNum) -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", rs.getLong("id"));
                    view.put("lineNo", rs.getInt("line_no"));
                    view.put("lineType", rs.getString("line_type"));
                    view.put("productName", rs.getString("product_name"));
                    view.put("specification", rs.getString("specification"));
                    view.put("baseUnit", rs.getString("base_unit"));
                    view.put("quantity", rs.getBigDecimal("quantity"));
                    view.put("unitPrice", rs.getBigDecimal("unit_price"));
                    view.put("amount", rs.getBigDecimal("amount"));
                    view.put("remark", rs.getString("remark"));
                    return view;
                }, documentId);
    }

    private List<SalesItem> parseItems(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            List<String> columns = new ArrayList<>();
            root.path("columns").forEach(node -> columns.add(node.asText("")));
            int productIndex = findColumn(columns, List.of("品名", "产品", "名称"));
            int specIndex = findColumn(columns, List.of("规格", "型号"));
            int unitIndex = findColumn(columns, List.of("单位"));
            int quantityIndex = findColumn(columns, List.of("数量"));
            int priceIndex = findColumn(columns, List.of("单价"));
            int amountIndex = findColumn(columns, List.of("金额"));
            int remarkIndex = findColumn(columns, List.of("备注"));
            JsonNode rowTypes = root.path("rowTypes");
            List<SalesItem> items = new ArrayList<>();
            int lineNo = 1;
            int rowIndex = 0;
            for (JsonNode row : root.path("rows")) {
                String lineType = rowTypes.isArray() && rowIndex < rowTypes.size()
                        && "FEE".equalsIgnoreCase(rowTypes.path(rowIndex).asText())
                        ? "FEE" : "PRODUCT";
                rowIndex++;
                String product = cell(row, productIndex).trim();
                BigDecimal quantity = decimal(cell(row, quantityIndex), 4);
                BigDecimal price = decimal(cell(row, priceIndex), 6);
                BigDecimal amount = decimal(cell(row, amountIndex), 2);
                if (product.isBlank() && quantity.signum() == 0 && amount.signum() == 0) continue;
                if (product.isBlank()) throw new BusinessException("销售单商品名称不能为空");
                if ("FEE".equals(lineType)) {
                    quantity = BigDecimal.ONE.setScale(4);
                    if (amount.signum() < 0) throw new BusinessException("费用金额不能小于 0");
                    if (price.signum() == 0) price = amount.setScale(6, RoundingMode.HALF_UP);
                } else if (quantity.signum() <= 0) {
                    throw new BusinessException("销售单商品数量必须大于 0");
                }
                if (amount.signum() == 0 && price.signum() != 0) {
                    amount = quantity.multiply(price).setScale(2, RoundingMode.HALF_UP);
                }
                items.add(new SalesItem(lineNo++, lineType, product, cell(row, specIndex).trim(),
                        defaultText(cell(row, unitIndex), "FEE".equals(lineType) ? "项" : "件"), quantity, price, amount,
                        cell(row, remarkIndex).trim()));
            }
            return items;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("销售单商品明细格式不正确");
        }
    }

    private int findColumn(List<String> columns, List<String> aliases) {
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            if (aliases.stream().anyMatch(column::contains)) return i;
        }
        return -1;
    }

    private String cell(JsonNode row, int index) {
        return index >= 0 && row.isArray() && index < row.size() ? row.path(index).asText("") : "";
    }

    private BigDecimal decimal(String value, int scale) {
        try {
            String normalized = value == null ? "" : value.replace(",", "").trim();
            if (normalized.isBlank()) return BigDecimal.ZERO.setScale(scale);
            return new BigDecimal(normalized).setScale(scale, RoundingMode.HALF_UP);
        } catch (Exception exception) {
            throw new BusinessException("销售单数量或金额格式不正确");
        }
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Long existingReceipt(long companyId, Long documentId) {
        return jdbc.query("SELECT id FROM sales_order_receipt WHERE company_id = ? AND document_id = ? LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, companyId, documentId);
    }

    private void requireWarehouse(long companyId, Long warehouseId) {
        requireWarehouse(companyId, warehouseId, "请选择入库仓库");
    }

    private void requireWarehouse(long companyId, Long warehouseId, String emptyMessage) {
        if (warehouseId == null) throw new BusinessException(emptyMessage);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM warehouse WHERE id = ? AND company_id = ? AND enabled = 1
                """, Long.class, warehouseId, companyId);
        if (count == null || count == 0) throw new BusinessException("仓库不存在");
    }

    private Long createReturnTransfer(BusinessDocumentDO document, List<Map<String, Object>> items,
                                      long createdBy) {
        Long buyerCompanyId = document.getBuyerCompanyId();
        Long supplierCompanyId = document.getSupplierCompanyId();
        if (buyerCompanyId == null || supplierCompanyId == null) {
            throw new BusinessException("退货单供需企业信息不完整");
        }
        requireWarehouse(buyerCompanyId, document.getOutboundWarehouseId(), "退货方尚未选择出库仓库");
        requireWarehouse(supplierCompanyId, document.getInboundWarehouseId(), "收货方尚未选择入库仓库");

        Map<Long, OutboundStock> stocksByProduct = new java.util.TreeMap<>();
        for (Map<String, Object> item : items) {
            BigDecimal quantity = (BigDecimal) item.get("quantity");
            List<OutboundStock> matches = jdbc.query("""
                            SELECT product.id AS product_id, balance.quantity, balance.unit_price,
                                   balance.inventory_amount
                            FROM inventory_product product
                            JOIN inventory_balance balance ON balance.product_id = product.id
                              AND balance.company_id = product.company_id
                            WHERE product.company_id = ? AND balance.warehouse_id = ?
                              AND product.product_name = ? AND product.specification = ?
                              AND product.base_unit = ?
                            FOR UPDATE
                            """, (rs, rowNum) -> new OutboundStock(
                            rs.getLong("product_id"), quantity, rs.getBigDecimal("quantity"),
                            rs.getBigDecimal("unit_price"), rs.getBigDecimal("inventory_amount"),
                            String.valueOf(item.get("productName"))),
                    buyerCompanyId, document.getOutboundWarehouseId(),
                    String.valueOf(item.get("productName")), safeSpec(item.get("specification")),
                    String.valueOf(item.get("baseUnit")));
            if (matches.isEmpty()) {
                BigDecimal available = matches.isEmpty() ? BigDecimal.ZERO : matches.get(0).balanceQuantity();
                throw new BusinessException("退货出库库存不足：" + item.get("productName")
                        + "，可用 " + available.stripTrailingZeros().toPlainString()
                        + "，需要 " + quantity.stripTrailingZeros().toPlainString());
            }
            OutboundStock stock = matches.get(0);
            stocksByProduct.merge(stock.productId(), stock, (first, next) -> new OutboundStock(
                    first.productId(), first.quantity().add(next.quantity()), first.balanceQuantity(),
                    first.unitPrice(), first.inventoryAmount(), first.productName()));
        }

        for (OutboundStock stock : stocksByProduct.values()) {
            if (stock.balanceQuantity().compareTo(stock.quantity()) < 0) {
                throw new BusinessException("退货出库库存不足：" + stock.productName()
                        + "，可用 " + stock.balanceQuantity().stripTrailingZeros().toPlainString()
                        + "，合计需要 " + stock.quantity().stripTrailingZeros().toPlainString());
            }
        }

        Long transferId = ApplicationIds.next();
        jdbc.update("""
                INSERT INTO inventory_transfer
                (id, source_document_id, outbound_company_id, outbound_warehouse_id,
                 inbound_company_id, inbound_warehouse_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, transferId, document.getId(), buyerCompanyId, document.getOutboundWarehouseId(),
                supplierCompanyId, document.getInboundWarehouseId(), createdBy);
        for (OutboundStock stock : stocksByProduct.values()) {
            BigDecimal averageCost = stock.balanceQuantity().signum() == 0
                    ? BigDecimal.ZERO : stock.inventoryAmount().divide(
                    stock.balanceQuantity(), 6, RoundingMode.HALF_UP);
            BigDecimal amountDelta = averageCost.multiply(stock.quantity()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal nextQuantity = stock.balanceQuantity().subtract(stock.quantity());
            BigDecimal nextAmount = nextQuantity.signum() == 0
                    ? BigDecimal.ZERO.setScale(2) : stock.inventoryAmount().subtract(amountDelta);
            jdbc.update("""
                    UPDATE inventory_balance
                    SET quantity = ?, inventory_amount = ?, unit_price = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE company_id = ? AND warehouse_id = ? AND product_id = ?
                    """, nextQuantity, nextAmount,
                    nextQuantity.signum() == 0 ? BigDecimal.ZERO : averageCost,
                    buyerCompanyId, document.getOutboundWarehouseId(), stock.productId());
            jdbc.update("""
                    INSERT INTO inventory_transaction
                    (id, company_id, warehouse_id, product_id, biz_type, biz_id,
                     quantity_delta, balance_after, created_by)
                    VALUES (?, ?, ?, ?, 'RETURN_ORDER_OUTBOUND', ?, ?, ?, ?)
                    """, ApplicationIds.next(), buyerCompanyId, document.getOutboundWarehouseId(), stock.productId(),
                    transferId, stock.quantity().negate(), nextQuantity, createdBy);
        }
        return transferId;
    }

    private String safeSpec(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return "null".equals(text) ? "" : text.trim();
    }

    private Long findOrCreateProduct(long companyId, String name, String specification, String unit) {
        String safeSpec = specification == null || "null".equals(specification) ? "" : specification.trim();
        List<Long> existing = jdbc.query("""
                        SELECT id FROM inventory_product
                        WHERE company_id = ? AND product_name = ? AND specification = ? AND base_unit = ? LIMIT 1
                        """, (rs, rowNum) -> rs.getLong(1), companyId, name, safeSpec, unit);
        if (!existing.isEmpty()) return existing.get(0);
        Long productId = ApplicationIds.next();
        try {
            jdbc.update("""
                    INSERT INTO inventory_product (id, company_id, product_name, specification, base_unit)
                    VALUES (?, ?, ?, ?, ?)
                    """, productId, companyId, name, safeSpec, unit);
            return productId;
        } catch (DuplicateKeyException exception) {
            return jdbc.queryForObject("""
                    SELECT id FROM inventory_product
                    WHERE company_id = ? AND product_name = ? AND specification = ? AND base_unit = ?
                    """, Long.class, companyId, name, safeSpec, unit);
        }
    }

    private String createInboundNo() {
        return "RK-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private String statusText(String status) {
        if ("DRAFT".equals(status)) return "草稿";
        if ("ISSUED".equals(status)) return "待对方确认";
        if ("REJECTED".equals(status)) return "已驳回";
        if ("ACKNOWLEDGED".equals(status)) return "已通过待入库";
        if ("INBOUNDED".equals(status)) return "已通过并入库";
        if ("VOIDED".equals(status)) return "已作废";
        return status;
    }

    private boolean contractLocked(TradeContractRespDTO contract) {
        if ("COMPLETED".equals(contract.getStatus()) || "VOIDED".equals(contract.getStatus())) return true;
        if (contract.getStatus() == null) return false;
        Long count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM bilateral_action_request
                WHERE contract_id = ? AND status = 'PENDING'
                """, Long.class, contract.getId());
        return count != null && count > 0;
    }

    private void recordReconciliation(BusinessDocumentDO document) {
        if (reconciliationAccountService == null) return;
        List<SalesItem> items = parseItems(document.getContent());
        BigDecimal total = items.stream().map(SalesItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        LocalDate businessDate = LocalDate.now();
        try {
            String date = objectMapper.readTree(document.getContent()).path("date").asText("");
            if (!date.isBlank()) businessDate = LocalDate.parse(date);
        } catch (Exception ignored) {
            // 历史销售单日期缺失时以确认当天作为业务日期。
        }
        if ("RETURN_ORDER".equals(document.getDocumentType())) {
            reconciliationAccountService.recordReturnOrder(com.tradepass.module.trade.convert.document.BusinessDocumentConvert.INSTANCE.toDTO(document), total, businessDate,
                    AuthContext.userId(), document.getAcknowledgedAt());
        } else {
            reconciliationAccountService.recordSalesOrder(com.tradepass.module.trade.convert.document.BusinessDocumentConvert.INSTANCE.toDTO(document), total, businessDate,
                    AuthContext.userId(), document.getAcknowledgedAt());
        }
    }

    private boolean isConfirmableDocument(BusinessDocumentDO document) {
        return document != null && ("SALES_ORDER".equals(document.getDocumentType())
                || "RETURN_ORDER".equals(document.getDocumentType()));
    }

    private Long supplierCompanyId(BusinessDocumentDO document, TradeContractRespDTO contract) {
        if (document != null && document.getSupplierCompanyId() != null) {
            return document.getSupplierCompanyId();
        }
        if (contract == null) return null;
        return "PURCHASE".equalsIgnoreCase(contract.getDirection())
                ? contract.getCounterpartyCompanyId() : contract.getCompanyId();
    }

    private Long buyerCompanyId(BusinessDocumentDO document, TradeContractRespDTO contract) {
        if (document != null && document.getBuyerCompanyId() != null) {
            return document.getBuyerCompanyId();
        }
        if (contract == null) return null;
        return "PURCHASE".equalsIgnoreCase(contract.getDirection())
                ? contract.getCompanyId() : contract.getCounterpartyCompanyId();
    }

    private String documentLabel(BusinessDocumentDO document) {
        return document != null && "RETURN_ORDER".equals(document.getDocumentType())
                ? "退货单" : "销售单";
    }

    private record SalesItem(int lineNo, String lineType, String productName, String specification,
                             String baseUnit, BigDecimal quantity, BigDecimal unitPrice,
                             BigDecimal amount, String remark) {
    }

    private record OutboundStock(long productId, BigDecimal quantity, BigDecimal balanceQuantity,
                                 BigDecimal unitPrice, BigDecimal inventoryAmount,
                                 String productName) {
    }

    private record TransferRecord(long id, long outboundCompanyId, long outboundWarehouseId,
                                  long inboundCompanyId, long inboundWarehouseId, String status) {
    }

    private record InboundRecord(long id, long companyId, long warehouseId, String status) {
    }

    private record InventoryMovement(long companyId, long warehouseId, long productId,
                                     BigDecimal quantity) {
    }

    private record BalanceSnapshot(BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    }
}
