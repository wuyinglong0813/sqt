package com.tradepass.module.settlement.service.attachment;

import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;
import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations;
import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations.*;
import com.tradepass.module.settlement.service.reconciliation.ReconciliationAccountService;

import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.module.trade.api.approval.ApprovalOperations;
import com.tradepass.module.trade.api.approval.ApprovalOperations.*;
import com.tradepass.module.trade.api.bilateral.BilateralActionOperations;
import com.tradepass.module.trade.api.bilateral.BilateralActionOperations.*;

import com.tradepass.framework.mybatis.core.ApplicationIds;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import com.tradepass.module.settlement.dal.dataobject.attachment.ContractAttachmentDO;
import com.tradepass.module.settlement.dal.mysql.attachment.ContractAttachmentMapper;
import com.tradepass.module.settlement.convert.attachment.ContractAttachmentConvert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.tradepass.framework.storage.config.StorageProperties;

@Service
public class ContractAttachmentServiceImpl implements ContractAttachmentService {
    public static final String PAYMENT_VOUCHER = "PAYMENT_VOUCHER";
    public static final String INVOICE = "INVOICE";
    public static final String OTHER = "OTHER";

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.identity.api.directory.IdentityDirectoryOperations identityDirectory;

    private final ContractAttachmentMapper attachmentMapper;
    private final ContractReader contractMapper;
    private final AccessControlOperations accessControlService;
    private final AuditLogService auditLogService;
    private final ObjectStorageService objectStorageService;
    private final StorageProperties storageProperties;
    private final ReconciliationAccountService reconciliationAccountService;
    private final UserIdentityOperations userIdentityService;
    private ApprovalOperations approvalService;
    private BilateralActionOperations bilateralActionService;

    @Autowired
    public ContractAttachmentServiceImpl(ContractAttachmentMapper attachmentMapper,
                                     ContractReader contractMapper,
                                     AccessControlOperations accessControlService,
                                     AuditLogService auditLogService,
                                     ObjectStorageService objectStorageService,
                                     StorageProperties storageProperties,
                                     ReconciliationAccountService reconciliationAccountService,
                                     UserIdentityOperations userIdentityService) {
        this.attachmentMapper = attachmentMapper;
        this.contractMapper = contractMapper;
        this.accessControlService = accessControlService;
        this.auditLogService = auditLogService;
        this.objectStorageService = objectStorageService;
        this.storageProperties = storageProperties;
        this.reconciliationAccountService = reconciliationAccountService;
        this.userIdentityService = userIdentityService;
    }

    @Autowired
    public void setApprovalService(ApprovalOperations approvalService) {
        this.approvalService = approvalService;
    }

    @Autowired
    public void setBilateralActionService(BilateralActionOperations bilateralActionService) {
        this.bilateralActionService = bilateralActionService;
    }

    ContractAttachmentServiceImpl(ContractAttachmentMapper attachmentMapper,
                              ContractReader contractMapper,
                              AccessControlOperations accessControlService,
                              AuditLogService auditLogService) {
        this(attachmentMapper, contractMapper, accessControlService, auditLogService, null, null, null, null);
    }

    ContractAttachmentServiceImpl(ContractAttachmentMapper attachmentMapper,
                              ContractReader contractMapper,
                              AccessControlOperations accessControlService,
                              AuditLogService auditLogService,
                              ObjectStorageService objectStorageService,
                              StorageProperties storageProperties) {
        this(attachmentMapper, contractMapper, accessControlService, auditLogService,
                objectStorageService, storageProperties, null, null);
    }

    public List<Map<String, Object>> list(Long contractId, String category) {
        long companyId = AuthContext.requireCompanyId();
        String normalized = normalizeCategory(category);
        if (INVOICE.equals(normalized)) {
            accessControlService.requireAnyPermission(companyId, "contract_view", "contract_sign",
                    "reconciliation", "contract_attachment_upload", "invoice_view");
        } else {
            accessControlService.requireAnyPermission(companyId, "contract_view", "contract_sign",
                    "reconciliation", "contract_attachment_upload");
        }
        TradeContractRespDTO contract = requireContractParty(contractId, companyId);
        boolean contractReadOnly = bilateralActionService != null
                ? bilateralActionService.isContractReadOnly(contract)
                : "COMPLETED".equals(contract.getStatus()) || "VOIDED".equals(contract.getStatus());
        List<ContractAttachmentDO> rows = identityDirectory == null
                ? attachmentMapper.selectListWithUploader(contractId, normalized)
                : attachmentMapper.selectList(contractId, normalized);
        List<Map<String, Object>> views = new ArrayList<>();
        for (ContractAttachmentDO attachment : rows) {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", attachment.getId());
                    view.put("contractId", attachment.getContractId());
                    view.put("uploaderCompanyId", attachment.getUploaderCompanyId());
                    view.put("recipientCompanyId", attachment.getRecipientCompanyId());
                    view.put("uploaderCompanyName", identityDirectory == null ? attachment.getUploaderCompanyName()
                            : identityDirectory.companyNames(List.of(attachment.getUploaderCompanyId())).get(attachment.getUploaderCompanyId()));
                    view.put("uploaderName", identityDirectory == null ? attachment.getUploaderName()
                            : identityDirectory.userDisplayName(attachment.getCreatedBy()));
                    view.put("category", attachment.getCategory());
                    String status = attachment.getStatus();
                    Long recipientCompanyId = attachment.getRecipientCompanyId();
                    boolean canConfirm = "PENDING_CONFIRMATION".equals(status)
                            && Long.valueOf(companyId).equals(recipientCompanyId);
                    view.put("status", status);
                    view.put("statusText", canConfirm ? "待我方确认" : statusText(status));
                    view.put("originalName", attachment.getOriginalName());
                    view.put("contentType", attachment.getContentType());
                    view.put("fileSize", attachment.getFileSize());
                    view.put("voucherDate", attachment.getVoucherDate());
                    view.put("voucherAmount", attachment.getVoucherAmount());
                    view.put("invoiceNo", safe(attachment.getInvoiceNo()));
                    view.put("invoiceDate", attachment.getInvoiceDate());
                    view.put("invoiceAmount", attachment.getInvoiceAmount());
                    view.put("confirmedAt", attachment.getConfirmedAt());
                    view.put("signerName", safe(attachment.getSignerName()));
                    view.put("signedAt", attachment.getSignedAt());
                    view.put("rejectedReason", safe(attachment.getRejectedReason()));
                    view.put("canConfirm", canConfirm);
                    view.put("canResubmit", "REJECTED".equals(status)
                            && Long.valueOf(companyId).equals(attachment.getUploaderCompanyId()));
                    boolean uploader = Long.valueOf(companyId).equals(attachment.getUploaderCompanyId())
                            && AuthContext.userId() == attachment.getCreatedBy();
                    BilateralActionOperations.ActionState actionState = bilateralActionService == null
                            ? BilateralActionOperations.ActionState.empty()
                            : bilateralActionService.state(companyId,
                            BilateralActionOperations.ATTACHMENT, attachment.getId());
                    view.put("contractReadOnly", contractReadOnly);
                    view.put("pendingActionId", actionState.id());
                    view.put("pendingActionType", actionState.actionType());
                    view.put("pendingActionReason", actionState.reason());
                    view.put("canReviewAction", actionState.approverCompany());
                    view.put("canCancelAction", actionState.requesterUser());
                    view.put("canWithdraw", !contractReadOnly && uploader && "PENDING_CONFIRMATION".equals(status));
                    view.put("canDelete", !contractReadOnly && uploader && ("REJECTED".equals(status)
                            || (OTHER.equals(attachment.getCategory()) && "APPROVED".equals(status))));
                    view.put("canRequestVoid", !contractReadOnly && "APPROVED".equals(status)
                            && !OTHER.equals(attachment.getCategory()));
                    view.put("createdAt", attachment.getCreatedAt());
                    views.add(view);
        }
        return views;
    }

    @Transactional
    public Map<String, Object> upload(Long contractId, String category, String originalName,
                                      byte[] data, String voucherDate, String voucherAmount) {
        return upload(contractId, category, originalName, data, voucherDate, voucherAmount,
                null, null, null);
    }

    @Transactional
    public Map<String, Object> upload(Long contractId, String category, String originalName,
                                      byte[] data, String voucherDate, String voucherAmount,
                                      String invoiceNo, String invoiceDate, String invoiceAmount) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId,
                "contract_attachment_upload", "contract_sign", "order_create", "reconciliation");
        TradeContractRespDTO contract = requireContractParty(contractId, companyId);
        requireContractMutable(contract);
        String normalized = normalizeCategory(category);
        String contentType = FileTypeInspector.inspect(data);
        validateContentType(normalized, contentType, originalName);
        LocalDate parsedDate = parseDate(voucherDate);
        BigDecimal parsedAmount = parseAmount(voucherAmount);
        if (PAYMENT_VOUCHER.equals(normalized) && parsedAmount == null) {
            throw new BusinessException("请输入转款金额");
        }
        if (PAYMENT_VOUCHER.equals(normalized) && parsedDate == null) {
            throw new BusinessException("请选择转款日期");
        }
        String safeInvoiceNo = INVOICE.equals(normalized) ? createInvoiceNo() : "";
        LocalDate parsedInvoiceDate = parseInvoiceDate(invoiceDate);
        BigDecimal parsedInvoiceAmount = parseInvoiceAmount(invoiceAmount);
        if (INVOICE.equals(normalized)) {
            if (parsedInvoiceDate == null) throw new BusinessException("请选择开票日期");
            if (parsedInvoiceAmount == null) throw new BusinessException("请输入发票金额");
        }
        Long recipientCompanyId = Long.valueOf(companyId).equals(contract.getCompanyId())
                ? contract.getCounterpartyCompanyId() : contract.getCompanyId();
        if (recipientCompanyId == null) throw new BusinessException("合同对方企业信息不完整");
        String status = OTHER.equals(normalized) ? "APPROVED" : "PENDING_CONFIRMATION";
        String safeName = FileTypeInspector.sanitizeFileName(originalName, contentType);
        String sha256 = FileTypeInspector.sha256(data);
        ObjectStorageService.StoredObject stored = store(companyId, contractId, normalized,
                contentType, data, sha256);
        Long id = ApplicationIds.next();
        ContractAttachmentDO row = new ContractAttachmentDO();
        row.setId(id);
        row.setContractId(contractId);
        row.setUploaderCompanyId(companyId);
        row.setRecipientCompanyId(recipientCompanyId);
        row.setCategory(normalized);
        row.setStatus(status);
        row.setOriginalName(safeName);
        row.setContentType(contentType);
        row.setFileSize((long) data.length);
        row.setSha256(sha256);
        row.setVoucherDate(parsedDate);
        row.setVoucherAmount(parsedAmount);
        row.setInvoiceNo(emptyToNull(safeInvoiceNo));
        row.setInvoiceDate(parsedInvoiceDate);
        row.setInvoiceAmount(parsedInvoiceAmount);
        row.setCreatedBy(AuthContext.userId());
        if (stored == null) {
            row.setFileData(data);
        } else {
            row.setStorageProvider(stored.provider());
            row.setStorageBucket(stored.bucket());
            row.setObjectKey(stored.objectKey());
            row.setObjectVersionId(stored.versionId());
            row.setEtag(stored.etag());
            row.setEncryptionAlgorithm(stored.encryptionAlgorithm());
        }
        attachmentMapper.insert(row);
        auditLogService.log(companyId, "CONTRACT_ATTACHMENT", id,
                "UPLOAD", (OTHER.equals(normalized) ? "上传" : "提交待确认")
                        + categoryLabel(normalized) + " " + safeName);
        return list(contractId, normalized).stream()
                .filter(item -> id != null && id.equals(item.get("id")))
                .findFirst().orElseThrow(() -> new BusinessException("附件保存失败"));
    }

    @Transactional
    public Map<String, Object> decide(Long id, String decision, String reason) {
        return decide(id, decision, reason, null, null);
    }

    @Transactional
    public Map<String, Object> decide(Long id, String decision, String reason,
                                      String signatureName, byte[] signatureData) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId,
                "contract_attachment_upload", "contract_sign", "order_create",
                "reconciliation", "invoice_view");
        AttachmentRecord attachment = requireAttachment(id);
        TradeContractRespDTO contract = requireContractParty(attachment.contractId(), companyId);
        requireContractMutable(contract);
        if (!Long.valueOf(companyId).equals(attachment.recipientCompanyId())) {
            throw new BusinessException("仅接收方企业可以确认该资料");
        }
        String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(normalized) && !"REJECT".equals(normalized)) {
            throw new BusinessException("确认结果不正确");
        }
        if ("APPROVED".equals(attachment.status())) {
            return findView(attachment.contractId(), attachment.category(), id);
        }
        if (!"PENDING_CONFIRMATION".equals(attachment.status())) {
            throw new BusinessException("资料当前状态不能确认");
        }

        if ("REJECT".equals(normalized)) {
            String safeReason = reason == null ? "" : reason.trim();
            if (safeReason.isBlank() || safeReason.length() > 500) {
                throw new BusinessException("请输入驳回原因且不能超过 500 字");
            }
            attachmentMapper.markRejected(id, AuthContext.userId(), safeReason);
            auditLogService.log(companyId, "CONTRACT_ATTACHMENT", id,
                    "REJECT", "驳回" + categoryLabel(attachment.category()) + "：" + safeReason);
            recordAttachmentResult(attachment, companyId, "REJECTED",
                    categoryLabel(attachment.category()) + "已被驳回",
                    "对方已驳回" + categoryLabel(attachment.category()) + " " + attachment.originalName(),
                    safeReason);
            return findView(attachment.contractId(), attachment.category(), id);
        }

        validateApprovalMetadata(attachment);
        LocalDateTime confirmedAt = LocalDateTime.now();
        SignatureEvidence signature = null;
        if (PAYMENT_VOUCHER.equals(attachment.category())) {
            signature = prepareSignature(companyId, attachment.contractId(), id,
                    signatureName, signatureData, confirmedAt);
        }
        int updated = signature == null
                ? attachmentMapper.markApproved(id, AuthContext.userId(), confirmedAt)
                : updateApprovedWithSignature(id, confirmedAt, signature);
        if (updated == 0) return findView(attachment.contractId(), attachment.category(), id);
        if (reconciliationAccountService != null) {
            boolean invoice = INVOICE.equals(attachment.category());
            reconciliationAccountService.recordAttachment(contract, attachment.category(), id,
                    invoice ? attachment.invoiceDate() : attachment.voucherDate(),
                    invoice ? attachment.invoiceNo() : attachment.originalName(),
                    invoice ? attachment.invoiceAmount() : attachment.voucherAmount(),
                    attachment.uploaderCompanyId(), AuthContext.userId(), confirmedAt);
        }
        auditLogService.log(companyId, "CONTRACT_ATTACHMENT", id,
                "APPROVE", "确认" + categoryLabel(attachment.category()) + " " + attachment.originalName());
        recordAttachmentResult(attachment, companyId, "APPROVED",
                categoryLabel(attachment.category()) + "已通过",
                "对方已确认" + categoryLabel(attachment.category()) + " " + attachment.originalName(),
                null);
        return findView(attachment.contractId(), attachment.category(), id);
    }

private SignatureEvidence prepareSignature(long companyId, Long contractId, Long attachmentId,
                                                String originalName, byte[] data,
                                                LocalDateTime signedAt) {
        if (data == null || data.length == 0) {
            throw new BusinessException("确认转款凭证前请先完成手写签名");
        }
        if (data.length > 2L * 1024 * 1024) {
            throw new BusinessException("签名图片不能超过 2MB");
        }
        String contentType = FileTypeInspector.inspect(data);
        if (!"image/png".equals(contentType) && !"image/jpeg".equals(contentType)) {
            throw new BusinessException("签名仅支持 PNG 或 JPG 图片");
        }
        String signerName = userIdentityService == null
                ? "用户" + AuthContext.userId() : userIdentityService.currentDisplayName();
        if (signerName == null || signerName.isBlank()) signerName = "用户" + AuthContext.userId();
        String safeName = FileTypeInspector.sanitizeFileName(originalName, contentType);
        String sha256 = FileTypeInspector.sha256(data);
        ObjectStorageService.StoredObject stored = storeSignature(
                companyId, contractId, attachmentId, contentType, data, sha256);
        return new SignatureEvidence(signerName, signedAt, safeName, contentType,
                data.length, data, sha256, stored);
    }

    private int updateApprovedWithSignature(Long id, LocalDateTime confirmedAt,
                                            SignatureEvidence signature) {
        ObjectStorageService.StoredObject stored = signature.stored();
        if (stored == null) {
            return attachmentMapper.markApprovedWithInlineSignature(id, AuthContext.userId(), confirmedAt,
                    signature.signerName(), signature.signedAt(), signature.originalName(),
                    signature.contentType(), signature.fileSize(), signature.data(), signature.sha256());
        }
        return attachmentMapper.markApprovedWithStoredSignature(id, AuthContext.userId(), confirmedAt,
                signature.signerName(), signature.signedAt(), signature.originalName(),
                signature.contentType(), signature.fileSize(), signature.sha256(), stored.provider(),
                stored.bucket(), stored.objectKey(), stored.versionId(), stored.etag(),
                stored.encryptionAlgorithm());
    }

    @Transactional
    public String withdraw(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId,
                "contract_attachment_upload", "contract_sign", "order_create",
                "reconciliation", "invoice_view");
        AttachmentRecord attachment = requireAttachment(id);
        TradeContractRespDTO contract = requireContractParty(attachment.contractId(), companyId);
        requireContractMutable(contract);
        if (!Long.valueOf(companyId).equals(attachment.uploaderCompanyId())
                || AuthContext.userId() != attachment.createdBy()
                || !"PENDING_CONFIRMATION".equals(attachment.status())) {
            throw new BusinessException("仅上传人可以撤回待确认资料");
        }
        int updated = attachmentMapper.markWithdrawn(id, AuthContext.userId());
        if (updated != 1) throw new BusinessException("资料状态已变化，请刷新后重试");
        auditLogService.log(companyId, "CONTRACT_ATTACHMENT", id, "WITHDRAW",
                "撤回" + categoryLabel(attachment.category()) + " " + attachment.originalName());
        if (approvalService != null && attachment.recipientCompanyId() != null) {
            approvalService.recordResult(attachment.recipientCompanyId(), companyId,
                    attachment.category(), id, attachment.contractId(), "CANCELLED",
                    categoryLabel(attachment.category()) + "已撤回",
                    "上传方已撤回" + categoryLabel(attachment.category()) + " " + attachment.originalName(), null);
        }
        return categoryLabel(attachment.category()) + "已撤回";
    }

    @Transactional
    public String delete(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId,
                "contract_attachment_upload", "contract_sign", "order_create",
                "reconciliation", "invoice_view");
        AttachmentRecord attachment = requireAttachment(id);
        TradeContractRespDTO contract = requireContractParty(attachment.contractId(), companyId);
        requireContractMutable(contract);
        boolean deletableStatus = "REJECTED".equals(attachment.status())
                || (OTHER.equals(attachment.category()) && "APPROVED".equals(attachment.status()));
        if (!Long.valueOf(companyId).equals(attachment.uploaderCompanyId())
                || AuthContext.userId() != attachment.createdBy() || !deletableStatus) {
            throw new BusinessException("仅上传人可以删除已拒绝或其它资料");
        }
        int updated = attachmentMapper.markDeleted(id, AuthContext.userId());
        if (updated != 1) throw new BusinessException("资料已删除，请刷新列表");
        auditLogService.log(companyId, "CONTRACT_ATTACHMENT", id, "DELETE",
                "删除" + categoryLabel(attachment.category()) + " " + attachment.originalName());
        return categoryLabel(attachment.category()) + "已删除";
    }

    private void recordAttachmentResult(AttachmentRecord attachment, long sourceCompanyId,
                                        String resultStatus, String title, String detail,
                                        String rejectedReason) {
        if (approvalService == null || attachment.uploaderCompanyId() == null) return;
        approvalService.recordResult(attachment.uploaderCompanyId(), sourceCompanyId,
                attachment.category(), attachment.id(), attachment.contractId(), resultStatus,
                title, detail, rejectedReason);
    }

    public FileRespDTO getFile(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId,
                "contract_view", "contract_sign", "reconciliation", "contract_attachment_upload", "invoice_view");
        boolean invoiceOnly = accessControlService.hasPermission(companyId, "invoice_view")
                && !accessControlService.hasPermission(companyId, "contract_view")
                && !accessControlService.hasPermission(companyId, "contract_sign")
                && !accessControlService.hasPermission(companyId, "reconciliation")
                && !accessControlService.hasPermission(companyId, "contract_attachment_upload");
        ContractAttachmentDO file = attachmentMapper.selectFile(id, invoiceOnly ? 1 : 0);
        if (file == null) throw new BusinessException("附件不存在");
        FileRespDTO payload = ContractAttachmentConvert.INSTANCE.toFileRespDTO(file);
        requireContractParty(payload.contractId(), companyId);
        if (payload.data() != null) return payload;
        if (objectStorageService == null || !objectStorageService.isEnabled()
                || payload.objectKey() == null) {
            throw new BusinessException("附件内容暂不可用，请联系管理员");
        }
        byte[] data = objectStorageService.get(new ObjectStorageService.ObjectReference(
                payload.storageBucket(), payload.objectKey(), payload.objectVersionId(),
                payload.fileSize(), payload.sha256()));
        return payload.withData(data);
    }

    private ObjectStorageService.StoredObject store(long companyId, Long contractId, String category,
                                                     String contentType, byte[] data, String sha256) {
        if (objectStorageService == null || !objectStorageService.isEnabled()) return null;
        LocalDate today = LocalDate.now();
        String fileType = switch (category) {
            case PAYMENT_VOUCHER -> "payment-voucher";
            case INVOICE -> "invoice";
            default -> "attachment";
        };
        String key = keyPrefix() + "/file/" + companyId + "/" + contractId + "/"
                + fileType + "/" + today.getYear() + "/" + String.format("%02d", today.getMonthValue())
                + "/" + UUID.randomUUID() + "-" + sha256 + "."
                + FileTypeInspector.extension(contentType);
        return objectStorageService.putImmutable(key, data, contentType, sha256);
    }

    private ObjectStorageService.StoredObject storeSignature(long companyId, Long contractId,
                                                              Long attachmentId, String contentType,
                                                              byte[] data, String sha256) {
        if (objectStorageService == null || !objectStorageService.isEnabled()) return null;
        LocalDate today = LocalDate.now();
        String key = keyPrefix() + "/file/" + companyId + "/" + contractId
                + "/payment-voucher-confirmation/" + attachmentId + "/"
                + today.getYear() + "/" + String.format("%02d", today.getMonthValue())
                + "/" + UUID.randomUUID() + "-" + sha256 + "."
                + FileTypeInspector.extension(contentType);
        return objectStorageService.putImmutable(key, data, contentType, sha256);
    }

    private String keyPrefix() {
        String value = storageProperties == null ? "tradepass" : storageProperties.getKeyPrefix();
        value = value == null ? "" : value.trim().replaceAll("^/+|/+$", "");
        return value.isBlank() ? "tradepass" : value;
    }

    private TradeContractRespDTO requireContractParty(Long contractId, long companyId) {
        TradeContractRespDTO contract = contractMapper.selectById(contractId);
        if (contract == null || (!Long.valueOf(companyId).equals(contract.getCompanyId())
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

    private String normalizeCategory(String category) {
        String normalized = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        if (!PAYMENT_VOUCHER.equals(normalized)
                && !INVOICE.equals(normalized)
                && !OTHER.equals(normalized)) {
            throw new BusinessException("附件分类不正确");
        }
        return normalized;
    }

    private void validateContentType(String category, String contentType, String originalName) {
        if ((PAYMENT_VOUCHER.equals(category) || INVOICE.equals(category))
                && !FileTypeInspector.isImage(contentType)
                && !"application/pdf".equals(contentType)) {
            throw new BusinessException(categoryLabel(category) + "仅支持图片或 PDF");
        }
        if (OTHER.equals(category)
                && !FileTypeInspector.isImage(contentType)
                && !"application/pdf".equals(contentType)
                && !FileTypeInspector.isWord(contentType)) {
            throw new BusinessException("其它资料仅支持图片、PDF 或 Word");
        }
        if ("application/msword".equals(contentType)
                && (originalName == null || !originalName.toLowerCase(Locale.ROOT).endsWith(".doc"))) {
            throw new BusinessException("旧版 Word 文件扩展名必须为 .doc");
        }
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception exception) {
            throw new BusinessException("转款日期格式不正确");
        }
    }

    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            BigDecimal amount = new BigDecimal(value.trim());
            if (amount.signum() < 0) throw new NumberFormatException();
            return amount.setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (Exception exception) {
            throw new BusinessException("转款金额格式不正确");
        }
    }

    private LocalDate parseInvoiceDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception exception) {
            throw new BusinessException("开票日期格式不正确");
        }
    }

    private BigDecimal parseInvoiceAmount(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            BigDecimal amount = new BigDecimal(value.trim());
            if (amount.signum() < 0) throw new NumberFormatException();
            return amount.setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (Exception exception) {
            throw new BusinessException("发票金额格式不正确");
        }
    }

    private String createInvoiceNo() {
        String date = LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8).toUpperCase(Locale.ROOT);
        return "FP-" + date + "-" + suffix;
    }

    private AttachmentRecord requireAttachment(Long id) {
        ContractAttachmentDO row = attachmentMapper.selectRecord(id);
        if (row == null) throw new BusinessException("附件不存在");
        return new AttachmentRecord(
                row.getId(), row.getContractId(), row.getUploaderCompanyId(),
                row.getRecipientCompanyId(), row.getCategory(), row.getStatus(),
                row.getOriginalName(), row.getVoucherDate(), row.getVoucherAmount(),
                row.getInvoiceNo(), row.getInvoiceDate(), row.getInvoiceAmount(),
                row.getCreatedBy());
    }

    private void validateApprovalMetadata(AttachmentRecord attachment) {
        if (PAYMENT_VOUCHER.equals(attachment.category())
                && (attachment.voucherDate() == null || attachment.voucherAmount() == null)) {
            throw new BusinessException("转款凭证金额或日期不完整，请上传方重新提交");
        }
        if (INVOICE.equals(attachment.category())
                && (attachment.invoiceNo() == null || attachment.invoiceNo().isBlank()
                || attachment.invoiceDate() == null || attachment.invoiceAmount() == null)) {
            throw new BusinessException("发票系统编号、日期或金额不完整，请上传方重新提交");
        }
    }

    private Map<String, Object> findView(Long contractId, String category, Long id) {
        return list(contractId, category).stream()
                .filter(item -> id.equals(item.get("id")))
                .findFirst().orElseThrow(() -> new BusinessException("附件不存在"));
    }

    private String statusText(String status) {
        if ("PENDING_CONFIRMATION".equals(status)) return "待对方确认";
        if ("APPROVED".equals(status)) return "已通过";
        if ("REJECTED".equals(status)) return "已驳回";
        if ("VOIDED".equals(status)) return "已作废";
        if ("LEGACY".equals(status)) return "历史资料（未计入对账）";
        return status == null ? "" : status;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String categoryLabel(String category) {
        return switch (category) {
            case PAYMENT_VOUCHER -> "转款凭证";
            case INVOICE -> "发票";
            default -> "其它资料";
        };
    }

    private record AttachmentRecord(Long id, Long contractId, Long uploaderCompanyId,
                                    Long recipientCompanyId, String category, String status,
                                    String originalName, LocalDate voucherDate,
                                    BigDecimal voucherAmount, String invoiceNo,
                                    LocalDate invoiceDate, BigDecimal invoiceAmount,
                                    long createdBy) {
    }

    private record SignatureEvidence(String signerName, LocalDateTime signedAt,
                                     String originalName, String contentType, long fileSize,
                                     byte[] data, String sha256,
                                     ObjectStorageService.StoredObject stored) {
    }

    
}
