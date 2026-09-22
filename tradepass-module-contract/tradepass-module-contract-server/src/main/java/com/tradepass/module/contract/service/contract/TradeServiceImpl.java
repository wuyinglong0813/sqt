package com.tradepass.module.contract.service.contract;

import com.tradepass.module.contract.service.archive.ContractArchiveService;
import com.tradepass.module.contract.service.directory.ContractDirectoryService;
import com.tradepass.module.contract.service.signing.ContractSigningCancellationService;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.module.trade.api.approval.ApprovalOperations;
import com.tradepass.module.trade.api.approval.ApprovalOperations.*;
import com.tradepass.module.trade.api.ranking.RankingCacheOperations;
import com.tradepass.module.trade.api.ranking.RankingCacheOperations.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.controller.app.contract.vo.CreateContractReqVO;
import com.tradepass.module.contract.api.contract.dto.ContractRespDTO;
import com.tradepass.framework.common.pojo.PagePayload;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.contract.dal.dataobject.template.ContractTemplateDO;
import com.tradepass.module.contract.dal.dataobject.template.TemplateCategoryDO;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import com.tradepass.module.contract.dal.mysql.template.ContractTemplateMapper;
import com.tradepass.module.identity.api.counterparty.CounterpartyReader;
import com.tradepass.module.identity.api.counterparty.CounterpartyReader.*;
import com.tradepass.module.contract.dal.mysql.template.TemplateCategoryMapper;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TradeServiceImpl implements TradeService {
    private final CounterpartyReader counterpartyRelationMapper;
    private final TemplateCategoryMapper templateCategoryMapper;
    private final ContractTemplateMapper contractTemplateMapper;
    private final TradeContractMapper tradeContractMapper;
    private final CompanyReader companyMapper;
    private final AccessControlOperations accessControlService;
    private final AuditLogService auditLogService;
    private final RankingCacheOperations rankingCache;
    private final ContractArchiveService contractArchiveService;
    private ApprovalOperations approvalService;
    private ContractSigningCancellationService signingCancellation;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public TradeServiceImpl(CounterpartyReader counterpartyRelationMapper,
                        TemplateCategoryMapper templateCategoryMapper,
                        ContractTemplateMapper contractTemplateMapper,
                        TradeContractMapper tradeContractMapper,
                        CompanyReader companyMapper,
                        AccessControlOperations accessControlService,
                        AuditLogService auditLogService,
                        RankingCacheOperations rankingCache,
                        ContractArchiveService contractArchiveService) {
        this.counterpartyRelationMapper = counterpartyRelationMapper;
        this.templateCategoryMapper = templateCategoryMapper;
        this.contractTemplateMapper = contractTemplateMapper;
        this.tradeContractMapper = tradeContractMapper;
        this.companyMapper = companyMapper;
        this.accessControlService = accessControlService;
        this.auditLogService = auditLogService;
        this.rankingCache = rankingCache;
        this.contractArchiveService = contractArchiveService;
    }

    @Autowired(required = false)
    private com.tradepass.module.identity.api.directory.IdentityDirectoryOperations identityDirectory;

    @Autowired(required = false)
    private ContractDirectoryService contractDirectory;

    @Autowired
    public void setApprovalService(ApprovalOperations approvalService) {
        this.approvalService = approvalService;
    }

    @Autowired
    public void setSigningCancellation(ContractSigningCancellationService signingCancellation) {
        this.signingCancellation = signingCancellation;
    }

    TradeServiceImpl(CounterpartyReader counterpartyRelationMapper,
                 TemplateCategoryMapper templateCategoryMapper,
                 ContractTemplateMapper contractTemplateMapper,
                 TradeContractMapper tradeContractMapper,
                 CompanyReader companyMapper,
                 AccessControlOperations accessControlService,
                 AuditLogService auditLogService,
                 RankingCacheOperations rankingCache) {
        this(counterpartyRelationMapper, templateCategoryMapper, contractTemplateMapper,
                tradeContractMapper, companyMapper, accessControlService, auditLogService,
                rankingCache, null);
    }

    TradeServiceImpl(CounterpartyReader counterpartyRelationMapper,
                 TemplateCategoryMapper templateCategoryMapper,
                 ContractTemplateMapper contractTemplateMapper,
                 TradeContractMapper tradeContractMapper,
                 CompanyReader companyMapper,
                 AccessControlOperations accessControlService,
                 AuditLogService auditLogService) {
        this(counterpartyRelationMapper, templateCategoryMapper, contractTemplateMapper,
                tradeContractMapper, companyMapper, accessControlService, auditLogService, null, null);
    }

    

    

    

    

    

    

    

    public List<Map<String, Object>> listTemplateCategories() {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_template", "contract_sign");
        return templateCategoryMapper.selectMaps(new LambdaQueryWrapper<TemplateCategoryDO>()
                .select(TemplateCategoryDO::getId, TemplateCategoryDO::getName)
                .eq(TemplateCategoryDO::getCompanyId, companyId)
                .orderByAsc(TemplateCategoryDO::getSortOrder));
    }

    public Map<String, Object> addCategory(Map<String, Object> body) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "contract_template");
        String name = ((String) body.getOrDefault("name", "")).trim();
        if (name.isEmpty()) {
            throw new BusinessException("分类名不能为空");
        }
        TemplateCategoryDO existing = templateCategoryMapper.selectOne(new LambdaQueryWrapper<TemplateCategoryDO>()
                .eq(TemplateCategoryDO::getCompanyId, companyId)
                .eq(TemplateCategoryDO::getName, name)
                .last("LIMIT 1"));
        if (existing == null) {
            TemplateCategoryDO category = new TemplateCategoryDO();
            category.setCompanyId(companyId);
            category.setName(name);
            category.setSortOrder(0);
            templateCategoryMapper.insert(category);
        }
        return templateCategoryMapper.selectMaps(new LambdaQueryWrapper<TemplateCategoryDO>()
                .select(TemplateCategoryDO::getId, TemplateCategoryDO::getName)
                .eq(TemplateCategoryDO::getCompanyId, companyId)
                .eq(TemplateCategoryDO::getName, name)
                .last("LIMIT 1")).get(0);
    }

    public String deleteCategory(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "contract_template");
        templateCategoryMapper.delete(new LambdaQueryWrapper<TemplateCategoryDO>()
                .eq(TemplateCategoryDO::getId, id)
                .eq(TemplateCategoryDO::getCompanyId, companyId));
        return "已删除";
    }

    public List<Map<String, Object>> listTemplates(String keyword, String category) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_template", "contract_sign");
        return identityDirectory == null ? contractTemplateMapper.selectTemplateViews(companyId, trim(keyword), trim(category))
                : templateNames(contractTemplateMapper.selectTemplateViewsOwned(companyId, trim(keyword), trim(category)));
    }

    public PagePayload<Map<String, Object>> pageTemplates(String keyword, String category, int page, int size) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_template", "contract_sign");
        String cleanKeyword = trim(keyword);
        String cleanCategory = trim(category);
        LambdaQueryWrapper<ContractTemplateDO> query = new LambdaQueryWrapper<ContractTemplateDO>()
                .eq(ContractTemplateDO::getCompanyId, companyId);
        if (cleanKeyword != null && !cleanKeyword.isBlank()) {
            query.like(ContractTemplateDO::getName, cleanKeyword);
        }
        if (cleanCategory != null && !cleanCategory.isBlank() && !"all".equalsIgnoreCase(cleanCategory)) {
            query.eq(ContractTemplateDO::getCategory, cleanCategory);
        }
        long total = contractTemplateMapper.selectCount(query);
        long offset = (long) (normalizedPage - 1) * normalizedSize;
        List<Map<String, Object>> items = identityDirectory == null
                ? contractTemplateMapper.selectTemplatePageViews(companyId, cleanKeyword, cleanCategory, normalizedSize, offset)
                : templateNames(contractTemplateMapper.selectTemplatePageViewsOwned(companyId, cleanKeyword, cleanCategory, normalizedSize, offset));
        return PagePayload.of(items, total, normalizedPage, normalizedSize);
    }

    public Map<String, Object> getTemplate(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_template", "contract_sign");
        Map<String, Object> template = identityDirectory == null ? contractTemplateMapper.selectTemplateView(id, companyId)
                : contractTemplateMapper.selectTemplateViewOwned(id, companyId);
        if (identityDirectory != null && template != null) templateNames(List.of(template));
        if (template == null || template.isEmpty()) {
            throw new BusinessException("模板不存在");
        }
        return template;
    }

    private List<Map<String, Object>> templateNames(List<Map<String, Object>> templates) {
        var ids = new java.util.LinkedHashSet<Long>();
        templates.forEach(template -> {
            if (template.get("createdBy") instanceof Number id) ids.add(id.longValue());
            if (template.get("updatedBy") instanceof Number id) ids.add(id.longValue());
        });
        var names = identityDirectory.userNicknames(List.copyOf(ids));
        templates.forEach(template -> {
            if (template.get("createdBy") instanceof Number id && names.get(id.longValue()) != null) template.put("createdByName", names.get(id.longValue()));
            if (template.get("updatedBy") instanceof Number id && names.get(id.longValue()) != null) template.put("updatedByName", names.get(id.longValue()));
        });
        return templates;
    }

    public Map<String, Object> createTemplate(Map<String, Object> body) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "contract_template");
        String name = string(body.getOrDefault("name", ""));
        String category = string(body.getOrDefault("category", ""));
        String content = string(body.getOrDefault("content", ""));
        if (name.isBlank()) {
            throw new BusinessException("模板名称不能为空");
        }
        ContractTemplateDO template = new ContractTemplateDO();
        template.setCompanyId(companyId);
        template.setName(name);
        template.setCategory(category);
        template.setContent(content);
        template.setCreatedBy(AuthContext.userId());
        contractTemplateMapper.insert(template);
        auditLogService.log(companyId, "CONTRACT_TEMPLATE", template.getId(), "CREATE", "创建模板 " + name);
        return contractTemplateMapper.selectMaps(new LambdaQueryWrapper<ContractTemplateDO>()
                .select(ContractTemplateDO::getId, ContractTemplateDO::getName, ContractTemplateDO::getCategory,
                        ContractTemplateDO::getContent, ContractTemplateDO::getCreatedBy, ContractTemplateDO::getUpdatedBy,
                        ContractTemplateDO::getCreatedAt, ContractTemplateDO::getUpdatedAt)
                .eq(ContractTemplateDO::getId, template.getId())).get(0);
    }

    public Map<String, Object> updateTemplate(Long id, Map<String, Object> body) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "contract_template");
        String name = string(body.getOrDefault("name", ""));
        String category = string(body.getOrDefault("category", ""));
        String content = string(body.getOrDefault("content", ""));
        if (name.isBlank()) {
            throw new BusinessException("模板名称不能为空");
        }
        contractTemplateMapper.update(new LambdaUpdateWrapper<ContractTemplateDO>()
                .eq(ContractTemplateDO::getId, id)
                .eq(ContractTemplateDO::getCompanyId, companyId)
                .set(ContractTemplateDO::getName, name)
                .set(ContractTemplateDO::getCategory, category)
                .set(ContractTemplateDO::getContent, content)
                .set(ContractTemplateDO::getUpdatedBy, AuthContext.userId()));
        List<Map<String, Object>> updated = contractTemplateMapper.selectMaps(new LambdaQueryWrapper<ContractTemplateDO>()
                .select(ContractTemplateDO::getId, ContractTemplateDO::getName, ContractTemplateDO::getCategory,
                        ContractTemplateDO::getContent, ContractTemplateDO::getCreatedBy, ContractTemplateDO::getUpdatedBy,
                        ContractTemplateDO::getCreatedAt, ContractTemplateDO::getUpdatedAt)
                .eq(ContractTemplateDO::getId, id)
                .eq(ContractTemplateDO::getCompanyId, companyId));
        if (updated.isEmpty()) {
            throw new BusinessException("模板不存在");
        }
        auditLogService.log(companyId, "CONTRACT_TEMPLATE", id, "UPDATE", "更新模板 " + name);
        return updated.get(0);
    }

    public String deleteTemplate(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "contract_template");
        int deleted = contractTemplateMapper.delete(new LambdaQueryWrapper<ContractTemplateDO>()
                .eq(ContractTemplateDO::getId, id)
                .eq(ContractTemplateDO::getCompanyId, companyId));
        if (deleted == 0) {
            throw new BusinessException("模板不存在");
        }
        auditLogService.log(companyId, "CONTRACT_TEMPLATE", id, "DELETE", "删除合同模板");
        return "已删除";
    }

    public ContractRespDTO getContract(Long id) {
        long companyId = AuthContext.requireCompanyId();
        requireContractReadPermission(companyId);
        TradeContractDO contract = tradeContractMapper.selectById(id);
        if (contract == null || !isContractParty(contract, companyId)
                || !isContractVisibleTo(contract, companyId)) {
            throw new BusinessException("合同不存在");
        }
        return toReadContractPayload(contract, companyId);
    }

    public ContractRespDTO contractForElectronicSignature(Long id, long viewerCompanyId) {
        TradeContractDO contract = tradeContractMapper.selectById(id);
        if (contract == null || !isContractParty(contract, viewerCompanyId)) {
            throw new BusinessException("合同不存在");
        }
        return toContractPayload(contract, viewerCompanyId);
    }

    @Transactional
    public void activateAfterElectronicSignature(Long id, int expectedVersion, long completedBy) {
        TradeContractDO contract = tradeContractMapper.selectByIdForUpdate(id);
        if (contract == null) throw new BusinessException("合同不存在");
        if (contract.getVersionNo() == null || contract.getVersionNo() != expectedVersion) {
            throw new BusinessException("签署任务版本与合同版本不一致");
        }
        if ("ACTIVE".equals(contract.getStatus())) return;
        if (!"PENDING".equals(contract.getStatus())) throw new BusinessException("合同状态已变化，不能生效");
        LocalDateTime approvedAt = LocalDateTime.now();
        int updated = tradeContractMapper.update(new LambdaUpdateWrapper<TradeContractDO>()
                .eq(TradeContractDO::getId, id).eq(TradeContractDO::getVersionNo, expectedVersion)
                .eq(TradeContractDO::getStatus, "PENDING")
                .set(TradeContractDO::getStatus, "ACTIVE")
                .set(TradeContractDO::getApprovedBy, completedBy)
                .set(TradeContractDO::getApprovedAt, approvedAt));
        if (updated != 1) throw new BusinessException("合同状态已变化，请刷新后重试");
        contract.setStatus("ACTIVE");
        contract.setApprovedBy(completedBy);
        contract.setApprovedAt(approvedAt);
        auditLogService.logAs(contract.getCompanyId(), completedBy, "CONTRACT", id,
                "ELECTRONIC_SIGN_FINISH", "双方电子签署完成，合同生效 " + contract.getContractNo());
        recordContractResult(contract, contract.getCounterpartyCompanyId(), "APPROVED",
                "合同已签署并生效", "双方电子签署已完成 " + contract.getContractNo());
    }

    @Transactional
    public void voidAfterElectronicAbolish(Long id, int expectedVersion, long completedBy) {
        TradeContractDO contract = tradeContractMapper.selectByIdForUpdate(id);
        if (contract == null) throw new BusinessException("合同不存在");
        if (contract.getVersionNo() == null || contract.getVersionNo() != expectedVersion) {
            throw new BusinessException("签署任务版本与合同版本不一致");
        }
        if ("VOIDED".equals(contract.getStatus())) return;
        int updated = tradeContractMapper.update(new LambdaUpdateWrapper<TradeContractDO>()
                .eq(TradeContractDO::getId, id).eq(TradeContractDO::getVersionNo, expectedVersion)
                .eq(TradeContractDO::getStatus, "ACTIVE")
                .set(TradeContractDO::getStatus, "VOIDED"));
        if (updated != 1) throw new BusinessException("合同状态已变化，请刷新后重试");
        auditLogService.logAs(contract.getCompanyId(), completedBy, "CONTRACT", id,
                "ELECTRONIC_ABOLISH_FINISH", "双方电子签署作废完成 " + contract.getContractNo());
    }

    private List<TradeContractDO> partyContracts(Long companyId, String name, String status, int limit, long offset) {
        return contractDirectory != null ? contractDirectory.partyContracts(companyId, name, status, limit, offset)
                : tradeContractMapper.selectPartyContracts(companyId, name, status, limit, offset);
    }
    private long partyContractCount(Long companyId, String name, String status) {
        return contractDirectory != null ? contractDirectory.partyContractCount(companyId, name, status)
                : tradeContractMapper.countPartyContracts(companyId, name, status);
    }

    public List<ContractRespDTO> listContracts(String counterpartyName) {
        long companyId = AuthContext.requireCompanyId();
        requireContractReadPermission(companyId);
        return partyContracts(companyId, trim(counterpartyName), null, 1000, 0)
                .stream().map(contract -> toReadContractPayload(contract, companyId)).toList();
    }

    public PagePayload<ContractRespDTO> pageContracts(String counterpartyName, String status, int page, int size) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        long companyId = AuthContext.requireCompanyId();
        requireContractReadPermission(companyId);
        String cleanName = trim(counterpartyName);
        String cleanStatus = trim(status);
        long total = partyContractCount(companyId, cleanName, cleanStatus);
        long offset = (long) (normalizedPage - 1) * normalizedSize;
        List<ContractRespDTO> items = partyContracts(
                        companyId, cleanName, cleanStatus, normalizedSize, offset)
                .stream().map(contract -> toReadContractPayload(contract, companyId)).toList();
        return PagePayload.of(items, total, normalizedPage, normalizedSize);
    }

    public Map<String, Object> contractSummary() {
        long companyId = AuthContext.requireCompanyId();
        requireContractReadPermission(companyId);
        return tradeContractMapper.selectContractSummary(companyId);
    }

    private void requireContractReadPermission(long companyId) {
        accessControlService.requireAnyPermission(companyId, "contract_view", "contract_sign",
                "reconciliation", "invoice_view", "contract_attachment_upload");
    }

    private ContractRespDTO toReadContractPayload(TradeContractDO contract, long companyId) {
        boolean fullContent = accessControlService.hasPermission(companyId, "contract_view")
                || accessControlService.hasPermission(companyId, "contract_sign");
        return toContractPayload(contract, companyId, fullContent);
    }

    @Transactional
    public ContractRespDTO createContract(CreateContractReqVO request) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "contract_sign");
        validateDirection(request.direction(), true);
        LocalDate startDate = parseDate(request.startDate());
        LocalDate endDate = parseDate(request.endDate());
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessException("合同结束日期不能早于开始日期");
        }
        if (request.clientRequestId() != null && !request.clientRequestId().isBlank()) {
            TradeContractDO existing = tradeContractMapper.selectOne(new LambdaQueryWrapper<TradeContractDO>()
                    .eq(TradeContractDO::getCompanyId, companyId)
                    .eq(TradeContractDO::getClientRequestId, request.clientRequestId().trim())
                    .last("LIMIT 1"));
            if (existing != null) {
                return toContractPayload(existing, companyId);
            }
        }

        CompanyRespDTO counterparty = requireActiveCounterparty(companyId, request.counterpartyCompanyId());
        if (counterparty.getId() == companyId) {
            throw new BusinessException("合同对方不能是当前企业");
        }
        CompanyRespDTO initiator = companyMapper.selectById(companyId);
        if (initiator == null) {
            throw new BusinessException("当前企业不存在");
        }
        String direction = normalizeDirection(request.direction());
        String contractName = request.name().trim();
        BigDecimal amount = requireNonNegativeAmount(request.amount(), "合同金额");
        TradeContractDO contract = new TradeContractDO();
        contract.setCompanyId(companyId);
        contract.setContractNo(normalizeBusinessNo(request.contractNo(), "HT"));
        contract.setCounterpartyCompanyId(counterparty.getId());
        contract.setCounterpartyName(counterparty.getName());
        contract.setDirection(direction);
        contract.setClientRequestId(trim(request.clientRequestId()));
        contract.setName(contractName);
        contract.setTemplateName(request.templateName());
        contract.setAmount(amount);
        contract.setStartDate(startDate);
        contract.setEndDate(endDate);
        contract.setTerms(normalizeContractTerms(request.terms(), contractName,
                initiator.getName(), counterparty.getName(), direction));
        contract.setVersionNo(1);
        contract.setStatus("PENDING");
        contract.setInitiatorHidden(false);
        contract.setInitiatedBy(AuthContext.userId());
        try {
            tradeContractMapper.insert(contract);
        } catch (DuplicateKeyException duplicate) {
            TradeContractDO existing = tradeContractMapper.selectOne(new LambdaQueryWrapper<TradeContractDO>()
                    .eq(TradeContractDO::getCompanyId, companyId)
                    .eq(TradeContractDO::getClientRequestId, request.clientRequestId().trim())
                    .last("LIMIT 1"));
            if (existing != null) {
                return toContractPayload(existing, companyId);
            }
            throw duplicate;
        }
        auditLogService.log(companyId, "CONTRACT", contract.getId(), "CREATE",
                "发起合同 " + contract.getContractNo() + "，金额 " + amount);
        return toContractPayload(contract, companyId);
    }

    @Transactional
    public String approveContract(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "contract_sign");
        TradeContractDO contract = ensurePendingIncomingContract(id, companyId);
        LocalDateTime approvedAt = LocalDateTime.now();
        tradeContractMapper.update(new LambdaUpdateWrapper<TradeContractDO>()
                .eq(TradeContractDO::getId, id)
                .eq(TradeContractDO::getStatus, "PENDING")
                .set(TradeContractDO::getStatus, "ACTIVE")
                .set(TradeContractDO::getApprovedBy, AuthContext.userId())
                .set(TradeContractDO::getApprovedAt, approvedAt));
        contract.setStatus("ACTIVE");
        contract.setApprovedBy(AuthContext.userId());
        contract.setApprovedAt(approvedAt);
        if (contractArchiveService != null) {
            contractArchiveService.archiveOnApproval(
                    toContractPayload(contract, companyId), AuthContext.userId());
        }
        auditLogService.log(companyId, "CONTRACT", id, "APPROVE",
                "签署合同 " + contract.getContractNo());
        recordContractResult(contract, companyId, "APPROVED",
                "合同已签署并生效", "对方已签署合同 " + contract.getContractNo());
        return "合同已签署生效";
    }

    @Transactional
    public String rejectContract(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "contract_sign");
        TradeContractDO contract = ensurePendingIncomingContract(id, companyId);
        if (signingCancellation != null) signingCancellation.cancelForChange(contract, "对方拒绝签署");
        int updated = tradeContractMapper.update(new LambdaUpdateWrapper<TradeContractDO>()
                .eq(TradeContractDO::getId, id)
                .eq(TradeContractDO::getStatus, "PENDING")
                .set(TradeContractDO::getStatus, "REJECTED")
                .set(TradeContractDO::getApprovedBy, AuthContext.userId())
                .set(TradeContractDO::getApprovedAt, LocalDateTime.now()));
        if (updated != 1) throw new BusinessException("合同状态已变化，请刷新后重试");
        auditLogService.log(companyId, "CONTRACT", id, "REJECT",
                "拒绝合同 " + contract.getContractNo());
        recordContractResult(contract, companyId, "REJECTED",
                "合同已被拒绝", "对方已拒绝合同 " + contract.getContractNo());
        return "合同已拒绝";
    }

    @Transactional
    public String cancelContract(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "contract_sign");
        TradeContractDO contract = ensureOutgoingContract(id, companyId, List.of("PENDING"));
        if (signingCancellation != null) signingCancellation.cancelForChange(contract, "发起方撤回合同");
        int updated = tradeContractMapper.update(new LambdaUpdateWrapper<TradeContractDO>()
                .eq(TradeContractDO::getId, id)
                .eq(TradeContractDO::getCompanyId, companyId)
                .eq(TradeContractDO::getStatus, "PENDING")
                .set(TradeContractDO::getStatus, "CANCELLED")
                .set(TradeContractDO::getApprovedBy, null)
                .set(TradeContractDO::getApprovedAt, null));
        if (updated != 1) throw new BusinessException("合同状态已变化，请刷新后重试");
        auditLogService.log(companyId, "CONTRACT", id, "CANCEL",
                "撤回待签合同 " + contract.getContractNo());
        if (approvalService != null && contract.getCounterpartyCompanyId() != null) {
            approvalService.recordResult(contract.getCounterpartyCompanyId(), companyId,
                    "CONTRACT", id, null, "CANCELLED", "合同已被发起方撤回",
                    "发起方已撤回合同 " + contract.getContractNo(), null);
        }
        return "合同审批已撤回";
    }

    @Transactional
    public ContractRespDTO resubmitContract(Long id, CreateContractReqVO request) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "contract_sign");
        TradeContractDO existing = ensureOutgoingContract(
                id, companyId, List.of("PENDING", "REJECTED", "CANCELLED"));
        validateDirection(request.direction(), true);
        LocalDate startDate = parseDate(request.startDate());
        LocalDate endDate = parseDate(request.endDate());
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessException("合同结束日期不能早于开始日期");
        }
        CompanyRespDTO counterparty = requireActiveCounterparty(companyId, request.counterpartyCompanyId());
        if (!counterparty.getId().equals(existing.getCounterpartyCompanyId())) {
            throw new BusinessException("修改合同不能更换签署企业，请另行发起合同");
        }
        CompanyRespDTO initiator = companyMapper.selectById(companyId);
        if (initiator == null) throw new BusinessException("当前企业不存在");
        String direction = normalizeDirection(request.direction());
        String contractName = request.name().trim();
        BigDecimal amount = requireNonNegativeAmount(request.amount(), "合同金额");
        String terms = normalizeContractTerms(request.terms(), contractName,
                initiator.getName(), counterparty.getName(), direction);
        if (signingCancellation != null) signingCancellation.cancelForChange(existing, "合同修改，终止旧版本签署");
        int nextVersion = Math.max(1, existing.getVersionNo() == null ? 1 : existing.getVersionNo()) + 1;
        int updated = tradeContractMapper.update(new LambdaUpdateWrapper<TradeContractDO>()
                .eq(TradeContractDO::getId, id)
                .eq(TradeContractDO::getCompanyId, companyId)
                .eq(TradeContractDO::getVersionNo, existing.getVersionNo())
                .in(TradeContractDO::getStatus, List.of("PENDING", "REJECTED", "CANCELLED"))
                .set(TradeContractDO::getDirection, direction)
                .set(TradeContractDO::getName, contractName)
                .set(TradeContractDO::getTemplateName, request.templateName())
                .set(TradeContractDO::getAmount, amount)
                .set(TradeContractDO::getStartDate, startDate)
                .set(TradeContractDO::getEndDate, endDate)
                .set(TradeContractDO::getTerms, terms)
                .set(TradeContractDO::getVersionNo, nextVersion)
                .set(TradeContractDO::getStatus, "PENDING")
                .set(TradeContractDO::getInitiatorHidden, false)
                .set(TradeContractDO::getApprovedBy, null)
                .set(TradeContractDO::getApprovedAt, null));
        if (updated != 1) throw new BusinessException("合同状态已变化，请刷新后重试");
        auditLogService.log(companyId, "CONTRACT", id, "RESUBMIT",
                "修改并重新发起合同 " + existing.getContractNo() + "，版本 V" + nextVersion);
        return toContractPayload(tradeContractMapper.selectById(id), companyId);
    }

    @Transactional
    public String deleteContract(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "contract_sign");
        TradeContractDO contract = ensureOutgoingContract(id, companyId, List.of("REJECTED", "CANCELLED"));
        int updated = tradeContractMapper.update(new LambdaUpdateWrapper<TradeContractDO>()
                .eq(TradeContractDO::getId, id)
                .eq(TradeContractDO::getCompanyId, companyId)
                .in(TradeContractDO::getStatus, List.of("REJECTED", "CANCELLED"))
                .eq(TradeContractDO::getInitiatorHidden, false)
                .set(TradeContractDO::getInitiatorHidden, true));
        if (updated != 1) throw new BusinessException("合同状态已变化，请刷新后重试");
        auditLogService.log(companyId, "CONTRACT", id, "DELETE",
                "从发起方合同列表删除合同 " + contract.getContractNo());
        return "合同已从我方列表删除";
    }

    private void recordContractResult(TradeContractDO contract, long sourceCompanyId,
                                      String resultStatus, String title, String detail) {
        if (approvalService == null || contract.getCompanyId() == null || contract.getId() == null) return;
        approvalService.recordResult(contract.getCompanyId(), sourceCompanyId,
                "CONTRACT", contract.getId(), contract.getId(), resultStatus,
                title, detail, null);
        if (contract.getCounterpartyCompanyId() != null) {
            CompanyRespDTO initiator = companyMapper.selectById(contract.getCompanyId());
            String initiatorName = initiator == null || initiator.getName() == null
                    ? "发起公司" : initiator.getName();
            String contractName = trim(contract.getName());
            if (contractName == null || contractName.isBlank()) contractName = contract.getContractNo();
            if (contractName != null && !contractName.endsWith("合同")) contractName += "合同";
            String selfTitle = "APPROVED".equals(resultStatus)
                    ? "已同意签署" + initiatorName + "的" + contractName
                    : "已拒绝" + initiatorName + "的" + contractName;
            String selfDetail = "APPROVED".equals(resultStatus)
                    ? "我方已签署合同 " + contract.getContractNo()
                    : "我方已拒绝合同 " + contract.getContractNo();
            approvalService.recordResult(contract.getCounterpartyCompanyId(), contract.getCompanyId(),
                    "CONTRACT", contract.getId(), "APPROVED".equals(resultStatus) ? contract.getId() : null,
                    resultStatus,
                    selfTitle, selfDetail, null);
        }
    }

    private TradeContractDO ensureOutgoingContract(Long id, long companyId, List<String> allowedStatuses) {
        TradeContractDO contract = tradeContractMapper.selectOne(new LambdaQueryWrapper<TradeContractDO>()
                .eq(TradeContractDO::getId, id)
                .eq(TradeContractDO::getCompanyId, companyId)
                .eq(TradeContractDO::getInitiatorHidden, false)
                .in(TradeContractDO::getStatus, allowedStatuses)
                .last("LIMIT 1 FOR UPDATE"));
        if (contract == null) {
            throw new BusinessException("合同不存在或当前状态不能执行该操作");
        }
        return contract;
    }

    public List<ContractRespDTO> myInitiatedContracts() {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_view", "contract_sign");
        return tradeContractMapper.selectList(new LambdaQueryWrapper<TradeContractDO>()
                        .eq(TradeContractDO::getCompanyId, companyId)
                        .eq(TradeContractDO::getInitiatedBy, AuthContext.userId())
                        .eq(TradeContractDO::getInitiatorHidden, false)
                        .orderByDesc(TradeContractDO::getCreatedAt))
                .stream().map(contract -> toContractPayload(contract, companyId)).toList();
    }

    public List<ContractRespDTO> pendingContracts() {
        Long companyId = AuthContext.companyId();
        if (companyId == null) {
            return List.of();
        }
        if (!accessControlService.hasPermission(companyId, "contract_sign")) {
            return List.of();
        }
        return tradeContractMapper.selectContractsAwaitingSignature(companyId)
                .stream().map(contract -> toContractPayload(contract, companyId)).toList();
    }

    private TradeContractDO ensurePendingIncomingContract(Long id, long companyId) {
        TradeContractDO contract = tradeContractMapper.selectOne(new LambdaQueryWrapper<TradeContractDO>()
                .eq(TradeContractDO::getId, id)
                .eq(TradeContractDO::getCounterpartyCompanyId, companyId)
                .eq(TradeContractDO::getStatus, "PENDING")
                .last("LIMIT 1 FOR UPDATE"));
        if (contract == null) {
            throw new BusinessException("合同不存在或状态不是待审批");
        }
        return contract;
    }

    

    private int normalizePage(int page) {
        return Math.max(1, page);
    }

    private int normalizeSize(int size) {
        return Math.max(1, Math.min(size, 100));
    }

    private String limitClause(int page, int size) {
        long offset = (long) (page - 1) * size;
        return "LIMIT " + size + " OFFSET " + offset;
    }

    

    private ContractRespDTO toContractPayload(TradeContractDO contract, long viewerCompanyId) {
        return toContractPayload(contract, viewerCompanyId, true);
    }

    private ContractRespDTO toContractPayload(TradeContractDO contract, long viewerCompanyId, boolean fullContent) {
        boolean outgoing = contract.getCompanyId() == viewerCompanyId;
        String initiatorCompanyName = companyName(contract.getCompanyId());
        String counterpartyCompanyName = companyName(contract.getCounterpartyCompanyId());
        if ("未知企业".equals(counterpartyCompanyName)
                && contract.getCounterpartyName() != null
                && !contract.getCounterpartyName().isBlank()) {
            counterpartyCompanyName = contract.getCounterpartyName();
        }
        boolean initiatorIsSupplier = "SALE".equalsIgnoreCase(contract.getDirection());
        String supplierCompanyName = initiatorIsSupplier
                ? initiatorCompanyName : counterpartyCompanyName;
        String buyerCompanyName = initiatorIsSupplier
                ? counterpartyCompanyName : initiatorCompanyName;
        Long viewerCounterpartyId = outgoing ? contract.getCounterpartyCompanyId() : contract.getCompanyId();
        String viewerCounterpartyName = outgoing
                ? counterpartyCompanyName
                : initiatorCompanyName;
        String viewerDirection = outgoing
                ? normalizeDirection(contract.getDirection())
                : invertDirection(contract.getDirection());
        return new ContractRespDTO(
                idString(contract.getId()),
                contract.getContractNo(),
                idString(contract.getCompanyId()),
                idString(contract.getCounterpartyCompanyId()),
                contract.getCounterpartyName(),
                contract.getDirection(),
                contract.getName(),
                contract.getTemplateName(),
                contract.getAmount(),
                contract.getStartDate() == null ? null : contract.getStartDate().toString(),
                contract.getEndDate() == null ? null : contract.getEndDate().toString(),
                fullContent ? contract.getTerms() : "",
                contract.getStatus(),
                contract.getVersionNo(),
                idString(contract.getInitiatedBy()),
                idString(contract.getApprovedBy()),
                contract.getApprovedAt() == null ? null : contract.getApprovedAt().toString(),
                contract.getCreatedAt() == null ? LocalDate.now().toString() : contract.getCreatedAt().toString(),
                supplierCompanyName,
                buyerCompanyName,
                String.valueOf(viewerCompanyId),
                idString(viewerCounterpartyId),
                viewerCounterpartyName,
                viewerDirection,
                outgoing ? "OUTGOING" : "INCOMING"
        );
    }

    private BigDecimal requireNonNegativeAmount(BigDecimal amount, String label) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(label + "不能为负数");
        }
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }
    private CompanyRespDTO requireActiveCounterparty(long companyId, Long requestedId) {
        if (requestedId == null) {
            throw new BusinessException("请选择已建立合作关系的企业");
        }
        CompanyRespDTO company = companyMapper.selectById(requestedId);
        if (company == null) {
            throw new BusinessException("合作企业不存在");
        }
        if (counterpartyRelationMapper.countActiveBetween(companyId, requestedId) <= 0) {
            throw new BusinessException("与该企业尚未建立有效合作关系");
        }
        return company;
    }

    private String normalizeContractTerms(String terms, String contractName, String initiatorName,
                                          String counterpartyName, String direction) {
        if (terms == null || terms.isBlank()) {
            return terms;
        }
        try {
            JsonNode parsed = objectMapper.readTree(terms);
            if (!(parsed instanceof ObjectNode root)) {
                throw new BusinessException("合同正文格式错误");
            }
            root.put("title", contractName);
            JsonNode fieldsNode = root.get("fields");
            if (fieldsNode instanceof ArrayNode fields) {
                String supplier = "SALE".equals(direction) ? initiatorName : counterpartyName;
                String buyer = "SALE".equals(direction) ? counterpartyName : initiatorName;
                for (JsonNode node : fields) {
                    if (node instanceof ObjectNode field) {
                        String key = field.path("key").asText();
                        if ("supplier".equals(key)) field.put("value", supplier);
                        if ("buyer".equals(key)) field.put("value", buyer);
                    }
                }
            }
            return objectMapper.writeValueAsString(root);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("合同正文格式错误");
        }
    }

    private String companyName(Long companyId) {
        CompanyRespDTO company = companyId == null ? null : companyMapper.selectById(companyId);
        return company == null ? "未知企业" : company.getName();
    }

    private String invertDirection(String direction) {
        return "PURCHASE".equalsIgnoreCase(direction) ? "SALE" : "PURCHASE";
    }

    private boolean isContractParty(TradeContractDO contract, long companyId) {
        return contract.getCompanyId() == companyId
                || (contract.getCounterpartyCompanyId() != null && contract.getCounterpartyCompanyId() == companyId);
    }

    private boolean isContractVisibleTo(TradeContractDO contract, long companyId) {
        if (contract.getCompanyId() == companyId) {
            return !Boolean.TRUE.equals(contract.getInitiatorHidden())
                    && !"DELETED".equals(contract.getStatus());
        }
        return !List.of("REJECTED", "CANCELLED", "DELETED").contains(contract.getStatus());
    }

    private void validateDirection(String direction, boolean required) {
        if (direction == null || direction.isBlank()) {
            if (required) {
                throw new BusinessException("交易方向不能为空");
            }
            return;
        }
        if (!"SALE".equalsIgnoreCase(direction) && !"PURCHASE".equalsIgnoreCase(direction)) {
            throw new BusinessException("交易方向只能是 SALE 或 PURCHASE");
        }
    }

    private String normalizeDirection(String direction) {
        return direction == null || direction.isBlank() ? "SALE" : direction.trim().toUpperCase();
    }

    private String normalizeBusinessNo(String requested, String prefix) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return prefix + "-" + day + "-" + suffix;
    }

    private String idString(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    private LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
