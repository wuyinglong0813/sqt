package com.tradepass.module.settlement.service.attachment;

import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations;
import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations.*;
import com.tradepass.module.settlement.api.attachment.AttachmentApprovalOperations;
import com.tradepass.module.settlement.api.attachment.AttachmentApprovalOperations.*;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import com.tradepass.module.settlement.dal.mysql.attachment.ContractAttachmentMapper;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
public class AttachmentApprovalServiceImpl implements AttachmentApprovalService {
    private final ContractAttachmentMapper attachmentMapper;
    private final IdentityDirectoryOperations identity;
    private final ContractReader contracts;
    public AttachmentApprovalServiceImpl(ContractAttachmentMapper attachmentMapper, IdentityDirectoryOperations identity, ContractReader contracts) {
        this.attachmentMapper = attachmentMapper; this.identity = identity; this.contracts = contracts;
    }
    public List<PendingAttachment> pendingAttachments(long companyId) {
        return attachmentMapper.selectPendingAttachments(companyId).stream().map(row -> {
                    var contract = contracts.selectById(row.getContractId());
                    var names = identity.companyNames(List.of(row.getUploaderCompanyId()));
                    if (contract == null || !names.containsKey(row.getUploaderCompanyId())) return null;
                    String category = row.getCategory();
                    boolean invoice = ContractAttachmentOperations.INVOICE.equals(category);
                    LocalDate businessDate = invoice ? row.getInvoiceDate() : row.getVoucherDate();
                    BigDecimal amount = invoice ? row.getInvoiceAmount() : row.getVoucherAmount();
                    Map<String, Object> view = item(row.getId(), category,
                            invoice ? "发票" : "转款凭证",
                            row.getUploaderCompanyId(), names.get(row.getUploaderCompanyId()),
                            row.getContractId(), contract.getContractNo(),
                            contract.getName(), row.getOriginalName(),
                            businessDate, amount, row.getCreatedAt() == null ? null : java.sql.Timestamp.valueOf(row.getCreatedAt()));
                    String contentType = row.getContentType();
                    view.put("contentType", contentType == null ? "" : contentType);
                    view.put("fileSize", row.getFileSize() == null ? 0L : row.getFileSize());
                    view.put("isImage", contentType != null && contentType.startsWith("image/"));
                    return new PendingAttachment(view, (LocalDateTime) view.get("createdAt"));
                }).filter(java.util.Objects::nonNull).toList();
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
