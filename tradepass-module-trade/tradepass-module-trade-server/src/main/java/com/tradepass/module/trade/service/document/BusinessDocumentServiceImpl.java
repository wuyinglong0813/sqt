package com.tradepass.module.trade.service.document;

import com.tradepass.module.trade.api.bilateral.BilateralActionOperations;
import com.tradepass.module.trade.api.bilateral.BilateralActionOperations.*;

import com.tradepass.module.trade.service.approval.ApprovalService;
import com.tradepass.module.trade.service.bilateral.BilateralActionService;
import com.tradepass.module.trade.service.inventory.SalesOrderInventoryService;

import static com.tradepass.module.trade.api.document.BusinessDocumentOperations.*;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentTemplateDO;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentMapper;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentTemplateMapper;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class BusinessDocumentServiceImpl implements BusinessDocumentService {
    public static final String SALES_ORDER = "SALES_ORDER";
    public static final String RETURN_ORDER = "RETURN_ORDER";

    private final BusinessDocumentTemplateMapper templateMapper;
    private final BusinessDocumentMapper documentMapper;
    private final ContractReader contractMapper;
    private final CompanyReader companyMapper;
    private final AccessControlOperations accessControlService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final SalesOrderInventoryService inventoryService;
    private final UserIdentityOperations userIdentityService;
    private ApprovalService approvalService;
    private BilateralActionService bilateralActionService;

    @Autowired
    public BusinessDocumentServiceImpl(BusinessDocumentTemplateMapper templateMapper,
                                   BusinessDocumentMapper documentMapper,
                                   ContractReader contractMapper,
                                   CompanyReader companyMapper,
                                   AccessControlOperations accessControlService,
                                   AuditLogService auditLogService,
                                   ObjectMapper objectMapper,
                                   SalesOrderInventoryService inventoryService,
                                   UserIdentityOperations userIdentityService) {
        this.templateMapper = templateMapper;
        this.documentMapper = documentMapper;
        this.contractMapper = contractMapper;
        this.companyMapper = companyMapper;
        this.accessControlService = accessControlService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.inventoryService = inventoryService;
        this.userIdentityService = userIdentityService;
    }

    BusinessDocumentServiceImpl(BusinessDocumentTemplateMapper templateMapper,
                            BusinessDocumentMapper documentMapper,
                            ContractReader contractMapper,
                            CompanyReader companyMapper,
                            AccessControlOperations accessControlService,
                            AuditLogService auditLogService,
                            ObjectMapper objectMapper) {
        this(templateMapper, documentMapper, contractMapper, companyMapper,
                accessControlService, auditLogService, objectMapper, null, null);
    }

    @Autowired
    public void setApprovalService(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Autowired
    public void setBilateralActionService(BilateralActionService bilateralActionService) {
        this.bilateralActionService = bilateralActionService;
    }

    public List<Map<String, Object>> listTemplates(String type) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(
                companyId, "contract_template", "contract_sign", "contract_view");
        String normalizedType = normalizeType(type);
        return templateMapper.selectList(new LambdaQueryWrapper<BusinessDocumentTemplateDO>()
                        .eq(BusinessDocumentTemplateDO::getCompanyId, companyId)
                        .eq(BusinessDocumentTemplateDO::getDocumentType, normalizedType)
                        .orderByDesc(BusinessDocumentTemplateDO::getUpdatedAt)
                        .orderByDesc(BusinessDocumentTemplateDO::getId))
                .stream().map(this::templateView).toList();
    }

    @Transactional
    public Map<String, Object> createTemplate(Map<String, Object> body) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "contract_template");
        String type = normalizeType(string(body.get("documentType")));
        String name = string(body.get("name")).trim();
        String sourceFileName = string(body.get("sourceFileName")).trim();
        String content = normalizeTemplateContent(type, string(body.get("content")));
        if (name.isBlank()) {
            throw new BusinessException("模板名称不能为空");
        }

        BusinessDocumentTemplateDO template = new BusinessDocumentTemplateDO();
        template.setCompanyId(companyId);
        template.setDocumentType(type);
        template.setName(name);
        template.setContent(content);
        template.setSourceFileName(sourceFileName);
        template.setCreatedBy(AuthContext.userId());
        templateMapper.insert(template);
        auditLogService.log(companyId, "BUSINESS_DOCUMENT_TEMPLATE", template.getId(),
                "CREATE", "上传" + typeLabel(type) + "模板 " + name);
        return templateView(templateMapper.selectById(template.getId()));
    }

    @Transactional
    public String deleteTemplate(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "contract_template");
        int deleted = templateMapper.delete(new LambdaQueryWrapper<BusinessDocumentTemplateDO>()
                .eq(BusinessDocumentTemplateDO::getId, id)
                .eq(BusinessDocumentTemplateDO::getCompanyId, companyId));
        if (deleted == 0) {
            throw new BusinessException("单据模板不存在");
        }
        auditLogService.log(companyId, "BUSINESS_DOCUMENT_TEMPLATE", id, "DELETE", "删除单据模板");
        return "已删除";
    }

    public List<Map<String, Object>> listDocuments(Long contractId, String type) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_view", "contract_sign");
        TradeContractRespDTO contract = requireContractParty(contractId, companyId);
        String normalizedType = normalizeType(type);
        return documentMapper.selectList(new LambdaQueryWrapper<BusinessDocumentDO>()
                        .eq(BusinessDocumentDO::getContractId, contractId)
                        .eq(BusinessDocumentDO::getDocumentType, normalizedType)
                        .isNull(BusinessDocumentDO::getDeletedAt)
                        .and(wrapper -> wrapper.ne(BusinessDocumentDO::getStatus, "DRAFT")
                                .or().eq(BusinessDocumentDO::getCompanyId, companyId))
                        .orderByDesc(BusinessDocumentDO::getCreatedAt)
                        .orderByDesc(BusinessDocumentDO::getId))
                .stream().map(document -> documentView(document, contract, companyId)).toList();
    }

    @Transactional
    public Map<String, Object> createDocument(Long contractId, Map<String, Object> body) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_sign", "order_create");
        String type = normalizeType(string(body.get("documentType")));
        Long templateId = longValue(body.get("templateId"));
        if (templateId == null) {
            throw new BusinessException("请选择单据模板");
        }
        TradeContractRespDTO contract = requireContractParty(contractId, companyId);
        requireContractMutable(contract);
        if (!"PENDING".equals(contract.getStatus()) && !"ACTIVE".equals(contract.getStatus())) {
            throw new BusinessException("当前合同状态不能创建" + typeLabel(type));
        }
        long supplierCompanyId = supplierCompanyId(contract);
        Long buyerCompanyId = buyerCompanyId(contract);
        if (buyerCompanyId == null) {
            throw new BusinessException("合同需方企业信息不完整");
        }
        if (SALES_ORDER.equals(type) && supplierCompanyId != companyId) {
            throw new BusinessException("仅合同供方可以创建销售单");
        }
        Long recipientCompanyId = companyId == supplierCompanyId ? buyerCompanyId : supplierCompanyId;
        BusinessDocumentTemplateDO template = templateMapper.selectOne(
                new LambdaQueryWrapper<BusinessDocumentTemplateDO>()
                        .eq(BusinessDocumentTemplateDO::getId, templateId)
                        .eq(BusinessDocumentTemplateDO::getCompanyId, companyId)
                        .eq(BusinessDocumentTemplateDO::getDocumentType, type)
                        .last("LIMIT 1"));
        if (template == null) {
            throw new BusinessException("所选模板不存在或类型不匹配");
        }

        CompanyRespDTO company = companyMapper.selectById(companyId);
        CompanyRespDTO recipientCompany = companyMapper.selectById(recipientCompanyId);
        BusinessDocumentDO document = new BusinessDocumentDO();
        document.setCompanyId(companyId);
        document.setRecipientCompanyId(recipientCompanyId);
        document.setSupplierCompanyId(supplierCompanyId);
        document.setBuyerCompanyId(buyerCompanyId);
        document.setContractId(contractId);
        document.setDocumentType(type);
        document.setSourceType(normalizeSourceType(string(body.get("sourceType"))));
        // 单据统一先保存为草稿，合同生效后再明确发布给对方确认。
        document.setStatus("DRAFT");
        document.setDocumentNo(createDocumentNo(type));
        document.setTemplateId(template.getId());
        document.setTemplateName(template.getName());
        String preparedByName = userIdentityService == null
                ? "用户" + AuthContext.userId() : userIdentityService.currentDisplayName();
        String snapshot = createSnapshot(type, template, contract, company,
                recipientCompany == null ? contract.getCounterpartyName() : recipientCompany.getName(),
                preparedByName);
        document.setContent(applySnapshotEdits(snapshot, body.get("content")));
        document.setCreatedBy(AuthContext.userId());
        documentMapper.insert(document);
        if (inventoryService != null) {
            inventoryService.saveDocumentItems(document);
        }
        auditLogService.log(companyId, "BUSINESS_DOCUMENT", document.getId(), "CREATE",
                "按模板 " + template.getName() + " 创建" + typeLabel(type) + "草稿");
        return documentView(documentMapper.selectById(document.getId()), contract, companyId);
    }

    @Transactional
    public Map<String, Object> updateDraft(Long id, Map<String, Object> body) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_sign", "order_create");
        BusinessDocumentDO document = requireOwnedEditableDocument(id, companyId);
        TradeContractRespDTO contract = requireContractParty(document.getContractId(), companyId);
        requireContractMutable(contract);
        if (!"PENDING".equals(contract.getStatus()) && !"ACTIVE".equals(contract.getStatus())) {
            throw new BusinessException("当前合同状态不能编辑单据草稿");
        }
        document.setContent(applySnapshotEdits(document.getContent(), body.get("content")));
        document.setStatus("DRAFT");
        document.setRejectedReason(null);
        documentMapper.updateById(document);
        if (inventoryService != null) {
            inventoryService.saveDocumentItems(document);
        }
        auditLogService.log(companyId, "BUSINESS_DOCUMENT", document.getId(),
                "UPDATE", "编辑" + typeLabel(document.getDocumentType()) + "草稿 " + document.getDocumentNo());
        return documentView(documentMapper.selectById(document.getId()), contract, companyId);
    }

    @Transactional
    public Map<String, Object> publishDraft(Long id) {
        return publishDraft(id, null);
    }

    @Transactional
    public Map<String, Object> publishDraft(Long id, Long warehouseId) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_sign", "order_create");
        BusinessDocumentDO document = requireOwnedEditableDocument(id, companyId);
        TradeContractRespDTO contract = requireContractParty(document.getContractId(), companyId);
        requireContractMutable(contract);
        if (!"ACTIVE".equals(contract.getStatus())) {
            throw new BusinessException("合同生效后才能发布" + typeLabel(document.getDocumentType()));
        }
        long supplierCompanyId = supplierCompanyId(contract);
        Long buyerCompanyId = buyerCompanyId(contract);
        if (buyerCompanyId == null) throw new BusinessException("合同需方企业信息不完整");
        if (SALES_ORDER.equals(document.getDocumentType()) && supplierCompanyId != companyId) {
            throw new BusinessException("仅单据制单方可以发布" + typeLabel(document.getDocumentType()));
        }
        document.setRecipientCompanyId(companyId == supplierCompanyId ? buyerCompanyId : supplierCompanyId);
        document.setSupplierCompanyId(supplierCompanyId);
        document.setBuyerCompanyId(buyerCompanyId);
        if (RETURN_ORDER.equals(document.getDocumentType())) {
            if (inventoryService == null) throw new BusinessException("退货库存服务尚未启用");
            inventoryService.selectReturnWarehouse(document, companyId, warehouseId);
        }
        document.setStatus("ISSUED");
        document.setRejectedReason(null);
        documentMapper.updateById(document);
        auditLogService.log(companyId, "BUSINESS_DOCUMENT", document.getId(),
                "SUBMIT", "提交" + typeLabel(document.getDocumentType()) + "等待对方确认 " + document.getDocumentNo());
        return documentView(documentMapper.selectById(document.getId()), contract, companyId);
    }

    public BusinessDocumentDO getDocument(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_view", "contract_sign");
        BusinessDocumentDO document = documentMapper.selectById(id);
        if (document == null || document.getDeletedAt() != null) {
            throw new BusinessException("单据不存在");
        }
        requireContractParty(document.getContractId(), companyId);
        if ("DRAFT".equals(document.getStatus())
                && !Long.valueOf(companyId).equals(document.getCompanyId())) {
            throw new BusinessException("单据草稿不存在");
        }
        return document;
    }

    @Transactional
    public String deleteDraft(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_sign", "order_create");
        BusinessDocumentDO document = documentMapper.selectOne(new LambdaQueryWrapper<BusinessDocumentDO>()
                .eq(BusinessDocumentDO::getId, id)
                .eq(BusinessDocumentDO::getCompanyId, companyId)
                .eq(BusinessDocumentDO::getCreatedBy, AuthContext.userId())
                .in(BusinessDocumentDO::getStatus, List.of("DRAFT", "REJECTED"))
                .isNull(BusinessDocumentDO::getDeletedAt)
                .last("LIMIT 1 FOR UPDATE"));
        if (document == null) throw new BusinessException("可删除的单据草稿不存在");
        if (bilateralActionService != null) {
            requireContractMutable(requireContractParty(document.getContractId(), companyId));
        }
        document.setDeletedBy(AuthContext.userId());
        document.setDeletedAt(LocalDateTime.now());
        documentMapper.updateById(document);
        auditLogService.log(companyId, "BUSINESS_DOCUMENT", id, "DELETE",
                "删除" + typeLabel(document.getDocumentType()) + "草稿 " + document.getDocumentNo());
        return typeLabel(document.getDocumentType()) + "草稿已删除";
    }

    @Transactional
    public String withdraw(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_sign", "order_create");
        BusinessDocumentDO document = documentMapper.selectOne(new LambdaQueryWrapper<BusinessDocumentDO>()
                .eq(BusinessDocumentDO::getId, id)
                .eq(BusinessDocumentDO::getCompanyId, companyId)
                .eq(BusinessDocumentDO::getCreatedBy, AuthContext.userId())
                .eq(BusinessDocumentDO::getStatus, "ISSUED")
                .isNull(BusinessDocumentDO::getDeletedAt)
                .last("LIMIT 1 FOR UPDATE"));
        if (document == null) throw new BusinessException("可撤回的单据不存在");
        if (bilateralActionService != null) {
            requireContractMutable(requireContractParty(document.getContractId(), companyId));
        }
        document.setStatus("WITHDRAWN");
        document.setDeletedBy(AuthContext.userId());
        document.setDeletedAt(LocalDateTime.now());
        documentMapper.updateById(document);
        auditLogService.log(companyId, "BUSINESS_DOCUMENT", id, "WITHDRAW",
                "撤回" + typeLabel(document.getDocumentType()) + " " + document.getDocumentNo());
        if (approvalService != null && document.getRecipientCompanyId() != null) {
            approvalService.recordResult(document.getRecipientCompanyId(), companyId,
                    document.getDocumentType(), document.getId(), document.getContractId(),
                    "CANCELLED", typeLabel(document.getDocumentType()) + "已撤回",
                    "发起方已撤回" + typeLabel(document.getDocumentType()) + " " + document.getDocumentNo(), null);
        }
        return typeLabel(document.getDocumentType()) + "已撤回";
    }

    public String typeLabel(String type) {
        return RETURN_ORDER.equals(type) ? "退货单" : "销售单";
    }

    public String defaultTemplateContent(String type) {
        normalizeType(type);
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode columns = content.putArray("columns");
        List<String> defaults = List.of("序号", "品名", "规格", "单位", "数量", "单价", "金额", "备注");
        defaults.forEach(columns::add);
        content.put("blankRows", 8);
        return content.toString();
    }

    private String normalizeTemplateContent(String type, String content) {
        if (content == null || content.isBlank()) {
            return defaultTemplateContent(type);
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isObject() || !root.path("columns").isArray()
                    || root.path("columns").isEmpty()) {
                return defaultTemplateContent(type);
            }
            return root.toString();
        } catch (Exception ignored) {
            return defaultTemplateContent(type);
        }
    }

    private String createSnapshot(String type, BusinessDocumentTemplateDO template,
                                  TradeContractRespDTO contract, CompanyRespDTO company,
                                  String counterpartyName, String preparedByName) {
        try {
            JsonNode templateContent = objectMapper.readTree(
                    normalizeTemplateContent(type, template.getContent()));
            List<String> targetColumns = new ArrayList<>();
            templateContent.path("columns").forEach(node -> targetColumns.add(node.asText("")));
            ProductSource source = extractProducts(contract.getTerms());
            List<FeeItem> fees = extractFees(contract.getTerms());

            ObjectNode snapshot = objectMapper.createObjectNode();
            snapshot.put("title", typeLabel(type));
            snapshot.put("companyName", company == null ? "本方企业" : company.getName());
            snapshot.put("counterpartyName", safe(counterpartyName));
            snapshot.put("contractNo", safe(contract.getContractNo()));
            snapshot.put("documentNo", "");
            snapshot.put("date", LocalDate.now().toString());
            snapshot.put("templateName", template.getName());
            snapshot.put("preparedByName", safe(preparedByName));
            snapshot.put("blankRows", Math.max(
                    8,
                    templateContent.path("blankRows").asInt(0)));
            ArrayNode columns = snapshot.putArray("columns");
            targetColumns.forEach(columns::add);
            ArrayNode rows = snapshot.putArray("rows");
            ArrayNode rowTypes = snapshot.putArray("rowTypes");
            for (int rowIndex = 0; rowIndex < source.rows().size(); rowIndex++) {
                List<String> row = source.rows().get(rowIndex);
                ArrayNode targetRow = rows.addArray();
                for (String targetColumn : targetColumns) {
                    targetRow.add(valueForTarget(targetColumn, rowIndex, source.columns(), row));
                }
                rowTypes.add("PRODUCT");
            }
            for (int feeIndex = 0; feeIndex < fees.size(); feeIndex++) {
                FeeItem fee = fees.get(feeIndex);
                ArrayNode targetRow = rows.addArray();
                for (String targetColumn : targetColumns) {
                    targetRow.add(valueForFeeTarget(targetColumn,
                            source.rows().size() + feeIndex, fee));
                }
                rowTypes.add("FEE");
            }
            snapshot.put("totalAmount", contract.getAmount() == null
                    ? "0" : contract.getAmount().stripTrailingZeros().toPlainString());
            return snapshot.toString();
        } catch (Exception exception) {
            throw new BusinessException("合同商品数据格式不正确，无法生成单据");
        }
    }

    private String applySnapshotEdits(String snapshot, Object content) {
        if (content == null) {
            return snapshot;
        }
        try {
            JsonNode edits = content instanceof String text
                    ? objectMapper.readTree(text)
                    : objectMapper.valueToTree(content);
            if (edits == null || !edits.isObject()) {
                throw new IllegalArgumentException("content must be an object");
            }

            ObjectNode result = (ObjectNode) objectMapper.readTree(snapshot);
            copyTextEdit(edits, result, "title", 80);
            copyTextEdit(edits, result, "companyName", 200);
            copyTextEdit(edits, result, "counterpartyName", 200);
            copyTextEdit(edits, result, "contractNo", 100);
            copyTextEdit(edits, result, "date", 30);
            copyTextEdit(edits, result, "totalAmount", 50);

            ArrayNode columns = readColumns(edits.has("columns")
                    ? edits.path("columns") : result.path("columns"));
            ArrayNode rows = readRows(edits.has("rows")
                    ? edits.path("rows") : result.path("rows"), columns.size());
            ArrayNode rowTypes = readRowTypes(edits.has("rowTypes")
                    ? edits.path("rowTypes") : result.path("rowTypes"), rows.size());
            int blankRows = edits.has("blankRows")
                    ? edits.path("blankRows").asInt(result.path("blankRows").asInt(10))
                    : result.path("blankRows").asInt(10);
            result.set("columns", columns);
            result.set("rows", rows);
            result.set("rowTypes", rowTypes);
            result.put("blankRows", Math.max(rows.size(), Math.min(50, Math.max(1, blankRows))));
            return result.toString();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("单据内容格式不正确");
        }
    }

    private void copyTextEdit(JsonNode source, ObjectNode target, String field, int maxLength) {
        if (!source.has(field)) return;
        JsonNode value = source.path(field);
        if (!value.isValueNode()) {
            throw new BusinessException("单据内容格式不正确");
        }
        String text = value.asText("");
        if (text.length() > maxLength) {
            throw new BusinessException("单据内容过长");
        }
        target.put(field, text);
    }

    private ArrayNode readColumns(JsonNode source) {
        if (!source.isArray() || source.isEmpty() || source.size() > 12) {
            throw new BusinessException("单据表格列格式不正确");
        }
        ArrayNode columns = objectMapper.createArrayNode();
        for (JsonNode item : source) {
            if (!item.isValueNode()) {
                throw new BusinessException("单据表格列格式不正确");
            }
            String value = item.asText("");
            if (value.isBlank() || value.length() > 40) {
                throw new BusinessException("单据表格列格式不正确");
            }
            columns.add(value);
        }
        return columns;
    }

    private ArrayNode readRows(JsonNode source, int columnCount) {
        if (!source.isArray() || source.size() > 100) {
            throw new BusinessException("单据表格内容格式不正确");
        }
        ArrayNode rows = objectMapper.createArrayNode();
        for (JsonNode sourceRow : source) {
            if (!sourceRow.isArray()) {
                throw new BusinessException("单据表格内容格式不正确");
            }
            ArrayNode row = rows.addArray();
            for (int index = 0; index < columnCount; index++) {
                JsonNode cell = index < sourceRow.size() ? sourceRow.get(index) : null;
                if (cell != null && !cell.isValueNode()) {
                    throw new BusinessException("单据表格内容格式不正确");
                }
                String value = cell == null ? "" : cell.asText("");
                if (value.length() > 200) {
                    throw new BusinessException("单据表格内容过长");
                }
                row.add(value);
            }
        }
        return rows;
    }

    private ArrayNode readRowTypes(JsonNode source, int rowCount) {
        ArrayNode rowTypes = objectMapper.createArrayNode();
        for (int index = 0; index < rowCount; index++) {
            String value = source.isArray() && index < source.size()
                    ? source.path(index).asText("PRODUCT") : "PRODUCT";
            rowTypes.add("FEE".equalsIgnoreCase(value) ? "FEE" : "PRODUCT");
        }
        return rowTypes;
    }

    private ProductSource extractProducts(String terms) {
        if (terms == null || terms.isBlank()) {
            return new ProductSource(List.of(), List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(terms);
            JsonNode sections = root.path("sections");
            if (sections.isArray()) {
                for (JsonNode section : sections) {
                    if ("table".equalsIgnoreCase(section.path("type").asText())) {
                        List<String> columns = new ArrayList<>();
                        section.path("columns").forEach(node -> columns.add(node.asText("")));
                        List<List<String>> rows = new ArrayList<>();
                        for (JsonNode rowNode : section.path("rows")) {
                            List<String> row = new ArrayList<>();
                            rowNode.forEach(node -> row.add(node.asText("")));
                            if (row.stream().anyMatch(value -> value != null && !value.isBlank()
                                    && !"0".equals(value.trim()) && !"0.00".equals(value.trim()))) {
                                rows.add(row);
                            }
                        }
                        return new ProductSource(columns, rows);
                    }
                }
            }
        } catch (Exception ignored) {
            // 历史纯文本合同没有商品表格，仍可生成空白标准单据。
        }
        return new ProductSource(List.of(), List.of());
    }

    private List<FeeItem> extractFees(String terms) {
        if (terms == null || terms.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(terms);
            for (JsonNode section : root.path("sections")) {
                if (!"fees".equalsIgnoreCase(section.path("type").asText())) continue;
                List<FeeItem> fees = new ArrayList<>();
                for (JsonNode item : section.path("items")) {
                    String feeType = item.path("feeType").asText(item.path("name").asText("其他费用"));
                    String amount = item.path("amount").asText("0");
                    String remark = item.path("remark").asText("");
                    if (!feeType.isBlank() || !amount.isBlank()) {
                        fees.add(new FeeItem(feeType, amount, remark));
                    }
                }
                return fees;
            }
        } catch (Exception ignored) {
            // 历史合同没有结构化费用项。
        }
        return List.of();
    }

    private String valueForFeeTarget(String target, int rowIndex, FeeItem fee) {
        String normalized = target == null ? "" : target.trim();
        if (normalized.contains("序号")) return String.valueOf(rowIndex + 1);
        if (normalized.contains("品名") || normalized.equals("名称") || normalized.contains("产品")) {
            return fee.feeType();
        }
        if (normalized.contains("单位")) return "项";
        if (normalized.contains("数量")) return "1";
        if (normalized.contains("单价") || normalized.contains("金额")) return fee.amount();
        if (normalized.contains("备注")) return fee.remark();
        return "";
    }

    private String valueForTarget(String target, int rowIndex,
                                  List<String> sourceColumns, List<String> sourceRow) {
        String normalized = target == null ? "" : target.trim();
        if (normalized.contains("序号")) {
            return String.valueOf(rowIndex + 1);
        }
        List<String> aliases;
        if (normalized.contains("品名") || normalized.equals("名称") || normalized.contains("产品")) {
            aliases = List.of("品名", "名称", "产品");
        } else if (normalized.contains("规格")) {
            aliases = List.of("规格", "型号");
        } else if (normalized.contains("单位")) {
            aliases = List.of("单位");
        } else if (normalized.contains("数量")) {
            aliases = List.of("数量");
        } else if (normalized.contains("单价")) {
            aliases = List.of("单价");
        } else if (normalized.contains("金额")) {
            aliases = List.of("金额");
        } else if (normalized.contains("备注")) {
            aliases = List.of("备注");
        } else {
            aliases = List.of(normalized);
        }
        for (int index = 0; index < sourceColumns.size(); index++) {
            String sourceColumn = sourceColumns.get(index);
            boolean matched = aliases.stream().anyMatch(sourceColumn::contains);
            if (matched && index < sourceRow.size()) {
                return safe(sourceRow.get(index));
            }
        }
        return "";
    }

    private TradeContractRespDTO requireContractParty(Long contractId, long companyId) {
        TradeContractRespDTO contract = contractMapper.selectById(contractId);
        if (contract == null
                || (!Long.valueOf(companyId).equals(contract.getCompanyId())
                && !Long.valueOf(companyId).equals(contract.getCounterpartyCompanyId()))) {
            throw new BusinessException("合同不存在");
        }
        return contract;
    }

    private void requireContractMutable(TradeContractRespDTO contract) {
        if (bilateralActionService != null) {
            bilateralActionService.requireContractMutable(contract);
        } else if ("COMPLETED".equals(contract.getStatus()) || "VOIDED".equals(contract.getStatus())) {
            throw new BusinessException("合同已结束或作废，仅允许查看");
        }
    }

    private BusinessDocumentDO requireOwnedEditableDocument(Long id, long companyId) {
        BusinessDocumentDO document = documentMapper.selectByIdForUpdate(id);
        if (document == null || document.getDeletedAt() != null || (!SALES_ORDER.equals(document.getDocumentType())
                && !RETURN_ORDER.equals(document.getDocumentType()))
                || !Long.valueOf(companyId).equals(document.getCompanyId())
                || (!"DRAFT".equals(document.getStatus()) && !"REJECTED".equals(document.getStatus()))) {
            throw new BusinessException("可编辑的单据草稿不存在");
        }
        return document;
    }

    private Map<String, Object> templateView(BusinessDocumentTemplateDO template) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", template.getId());
        view.put("documentType", template.getDocumentType());
        view.put("typeLabel", typeLabel(template.getDocumentType()));
        view.put("name", template.getName());
        view.put("sourceFileName", safe(template.getSourceFileName()));
        view.put("content", template.getContent());
        view.put("createdAt", template.getCreatedAt());
        view.put("updatedAt", template.getUpdatedAt());
        return view;
    }

    private Map<String, Object> documentView(BusinessDocumentDO document,
                                             TradeContractRespDTO contract,
                                             long viewerCompanyId) {
        Map<String, Object> view = new LinkedHashMap<>();
        boolean owner = Long.valueOf(viewerCompanyId).equals(document.getCompanyId());
        view.put("id", document.getId());
        view.put("documentType", document.getDocumentType());
        view.put("typeLabel", typeLabel(document.getDocumentType()));
        view.put("issuerCompanyId", document.getCompanyId());
        view.put("recipientCompanyId", document.getRecipientCompanyId());
        view.put("supplierCompanyId", resolvedSupplierCompanyId(document, contract));
        view.put("buyerCompanyId", resolvedBuyerCompanyId(document, contract));
        view.put("sourceType", document.getSourceType());
        view.put("status", document.getStatus());
        view.put("statusText", "ISSUED".equals(document.getStatus()) && !owner
                ? "待我方确认" : statusText(document.getStatus()));
        view.put("rejectedReason", safe(document.getRejectedReason()));
        view.put("documentNo", document.getDocumentNo());
        view.put("templateId", document.getTemplateId());
        view.put("templateName", document.getTemplateName());
        view.put("createdAt", document.getCreatedAt());
        boolean draft = "DRAFT".equals(document.getStatus()) || "REJECTED".equals(document.getStatus());
        boolean contractReadOnly = bilateralActionService != null
                ? bilateralActionService.isContractReadOnly(contract)
                : "COMPLETED".equals(contract.getStatus()) || "VOIDED".equals(contract.getStatus());
        BilateralActionOperations.ActionState actionState = bilateralActionService == null
                ? BilateralActionOperations.ActionState.empty()
                : bilateralActionService.state(viewerCompanyId,
                BilateralActionService.BUSINESS_DOCUMENT, document.getId());
        view.put("contractStatus", contract.getStatus());
        view.put("contractReadOnly", contractReadOnly);
        view.put("pendingActionId", actionState.id());
        view.put("pendingActionType", actionState.actionType());
        view.put("pendingActionReason", actionState.reason());
        view.put("canReviewAction", actionState.approverCompany());
        view.put("canCancelAction", actionState.requesterUser());
        view.put("canEditDraft", !contractReadOnly && owner && draft
                && ("PENDING".equals(contract.getStatus()) || "ACTIVE".equals(contract.getStatus())));
        view.put("canPublish", !contractReadOnly && owner && draft && "ACTIVE".equals(contract.getStatus()));
        boolean creator = owner && Long.valueOf(AuthContext.userId()).equals(document.getCreatedBy());
        view.put("canDeleteDraft", !contractReadOnly && creator && ("DRAFT".equals(document.getStatus())
                || "REJECTED".equals(document.getStatus())));
        view.put("canWithdraw", !contractReadOnly && creator && "ISSUED".equals(document.getStatus()));
        view.put("canRequestVoid", !contractReadOnly
                && List.of("ACKNOWLEDGED", "INBOUNDED").contains(document.getStatus()));
        return view;
    }

    private String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!SALES_ORDER.equals(normalized) && !RETURN_ORDER.equals(normalized)) {
            throw new BusinessException("单据类型不正确");
        }
        return normalized;
    }

    private String createDocumentNo(String type) {
        String prefix = RETURN_ORDER.equals(type) ? "TH" : "XS";
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        return prefix + "-" + date + "-" + suffix;
    }

    private String normalizeSourceType(String sourceType) {
        String normalized = sourceType == null ? "" : sourceType.trim().toUpperCase(Locale.ROOT);
        return "CONTRACT_DEFAULT".equals(normalized) ? "CONTRACT_DEFAULT" : "TEMPLATE";
    }

    private long supplierCompanyId(TradeContractRespDTO contract) {
        if ("PURCHASE".equalsIgnoreCase(contract.getDirection())) {
            if (contract.getCounterpartyCompanyId() == null) {
                throw new BusinessException("合同供方企业信息不完整");
            }
            return contract.getCounterpartyCompanyId();
        }
        return contract.getCompanyId();
    }

    private Long buyerCompanyId(TradeContractRespDTO contract) {
        return "PURCHASE".equalsIgnoreCase(contract.getDirection())
                ? contract.getCompanyId() : contract.getCounterpartyCompanyId();
    }

    private Long resolvedSupplierCompanyId(BusinessDocumentDO document, TradeContractRespDTO contract) {
        return document.getSupplierCompanyId() == null
                ? supplierCompanyId(contract) : document.getSupplierCompanyId();
    }

    private Long resolvedBuyerCompanyId(BusinessDocumentDO document, TradeContractRespDTO contract) {
        return document.getBuyerCompanyId() == null
                ? buyerCompanyId(contract) : document.getBuyerCompanyId();
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

    private Long longValue(Object value) {
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new BusinessException("模板 ID 格式不正确");
        }
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record ProductSource(List<String> columns, List<List<String>> rows) {
    }

    private record FeeItem(String feeType, String amount, String remark) {
    }
}
