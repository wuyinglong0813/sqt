#!/usr/bin/env python3.11
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ATT = ROOT / "tradepass-module-settlement/tradepass-module-settlement-server/src/main/java/com/tradepass/module/settlement/service/attachment"
REC = ROOT / "tradepass-module-settlement/tradepass-module-settlement-server/src/main/java/com/tradepass/module/settlement/service/reconciliation"


def patch_attachment_state():
    (ATT / "AttachmentStateService.java").write_text('''package com.tradepass.module.settlement.service.attachment;

import com.tradepass.module.settlement.api.attachment.AttachmentStateOperations;
import com.tradepass.module.settlement.dal.dataobject.attachment.ContractAttachmentDO;
import com.tradepass.module.settlement.dal.mysql.attachment.ContractAttachmentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttachmentStateService implements AttachmentStateOperations {
    private final ContractAttachmentMapper attachmentMapper;
    public AttachmentStateService(ContractAttachmentMapper attachmentMapper) { this.attachmentMapper = attachmentMapper; }
    public AttachmentState state(Long id, boolean includeDeleted) {
        ContractAttachmentDO row = includeDeleted
                ? attachmentMapper.selectStateIncludeDeleted(id)
                : attachmentMapper.selectState(id);
        return row == null ? null : new AttachmentState(
                row.getContractId(), row.getStatus(), row.getCategory(), row.getOriginalName());
    }
    public long effectiveCount(Long contractId) {
        Long count = attachmentMapper.countEffective(contractId);
        return count == null ? 0L : count;
    }
    @Transactional
    public long unfinishedCount(Long contractId) {
        attachmentMapper.touchUnfinished(contractId);
        Long count = attachmentMapper.countUnfinishedForUpdate(contractId);
        return count == null ? 0L : count;
    }
    @Transactional
    public int voidApproved(Long id) {
        return attachmentMapper.voidApproved(id);
    }
    public long pendingConfirmationCount(long companyId) {
        Long count = attachmentMapper.countPendingConfirmation(companyId);
        return count == null ? 0L : count;
    }
}
''')


def patch_attachment_approval():
    src = (ATT / "AttachmentApprovalService.java").read_text()
    src = src.replace("import org.springframework.jdbc.core.JdbcTemplate;\n",
                      "import com.tradepass.module.settlement.dal.mysql.attachment.ContractAttachmentMapper;\n")
    src = src.replace("    private final JdbcTemplate jdbc;\n    private final IdentityDirectoryOperations identity;\n    private final ContractReader contracts;\n    public AttachmentApprovalService(JdbcTemplate jdbc, IdentityDirectoryOperations identity, ContractReader contracts) {\n        this.jdbc = jdbc; this.identity = identity; this.contracts = contracts;\n    }",
                      "    private final ContractAttachmentMapper attachmentMapper;\n    private final IdentityDirectoryOperations identity;\n    private final ContractReader contracts;\n    public AttachmentApprovalService(ContractAttachmentMapper attachmentMapper, IdentityDirectoryOperations identity, ContractReader contracts) {\n        this.attachmentMapper = attachmentMapper; this.identity = identity; this.contracts = contracts;\n    }")
    src = src.replace("""        return jdbc.query(\"\"\"
                        SELECT attachment.id, attachment.contract_id, attachment.category,
                               attachment.original_name, attachment.content_type, attachment.voucher_date,
                               attachment.voucher_amount, attachment.invoice_date,
                               attachment.invoice_amount, attachment.file_size, attachment.created_at,
                               attachment.uploader_company_id AS source_company_id,
                               NULL AS source_company_name,
                               NULL AS contract_no, NULL AS contract_name
                        FROM contract_attachment attachment
                        
                        
                        WHERE attachment.recipient_company_id = ?
                          AND attachment.status = 'PENDING_CONFIRMATION'
                          AND attachment.category IN ('PAYMENT_VOUCHER', 'INVOICE')
                          AND attachment.deleted_at IS NULL
                        ORDER BY attachment.created_at DESC, attachment.id DESC
                        \"\"\", (rs, rowNum) -> {
                    var contract = contracts.selectById(rs.getLong("contract_id"));
                    var names = identity.companyNames(List.of(rs.getLong("source_company_id")));
                    if (contract == null || !names.containsKey(rs.getLong("source_company_id"))) return null;
                    String category = rs.getString("category");
                    boolean invoice = ContractAttachmentOperations.INVOICE.equals(category);
                    LocalDate businessDate = rs.getObject(
                            invoice ? "invoice_date" : "voucher_date", LocalDate.class);
                    BigDecimal amount = rs.getBigDecimal(
                            invoice ? "invoice_amount" : "voucher_amount");
                    Map<String, Object> view = item(rs.getLong("id"), category,
                            invoice ? "发票" : "转款凭证",
                            rs.getLong("source_company_id"), names.get(rs.getLong("source_company_id")),
                            rs.getLong("contract_id"), contract.getContractNo(),
                            contract.getName(), rs.getString("original_name"),
                            businessDate, amount, rs.getTimestamp("created_at"));
                    String contentType = rs.getString("content_type");
                    view.put("contentType", contentType == null ? "" : contentType);
                    view.put("fileSize", rs.getLong("file_size"));
                    view.put("isImage", contentType != null && contentType.startsWith("image/"));
                    return new PendingAttachment(view, (LocalDateTime) view.get("createdAt"));
                }, companyId).stream().filter(java.util.Objects::nonNull).toList();""",
                      """        return attachmentMapper.selectPendingAttachments(companyId).stream().map(row -> {
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
                }).filter(java.util.Objects::nonNull).toList();""")
    (ATT / "AttachmentApprovalService.java").write_text(src)


def patch_attachment_service():
    path = ATT / "ContractAttachmentService.java"
    src = path.read_text()
    src = src.replace("import org.springframework.jdbc.core.JdbcTemplate;\n",
                      "import com.tradepass.module.settlement.dal.dataobject.attachment.ContractAttachmentDO;\n"
                      "import com.tradepass.module.settlement.dal.mysql.attachment.ContractAttachmentMapper;\n")
    src = src.replace("import java.util.LinkedHashMap;\n", "import java.util.ArrayList;\nimport java.util.LinkedHashMap;\n")
    src = src.replace("    private final JdbcTemplate jdbc;", "    private final ContractAttachmentMapper attachmentMapper;")
    src = src.replace("    public ContractAttachmentService(JdbcTemplate jdbc,",
                      "    public ContractAttachmentService(ContractAttachmentMapper attachmentMapper,")
    src = src.replace("        this.jdbc = jdbc;", "        this.attachmentMapper = attachmentMapper;")
    src = src.replace("    ContractAttachmentService(JdbcTemplate jdbc,\n                              ContractReader contractMapper,",
                      "    ContractAttachmentService(ContractAttachmentMapper attachmentMapper,\n                              ContractReader contractMapper,")
    src = src.replace("    ContractAttachmentService(JdbcTemplate jdbc,\n                              ContractReader contractMapper,\n                              AccessControlOperations accessControlService,\n                              AuditLogService auditLogService,\n                              ObjectStorageService objectStorageService,\n                              StorageProperties storageProperties) {",
                      "    ContractAttachmentService(ContractAttachmentMapper attachmentMapper,\n                              ContractReader contractMapper,\n                              AccessControlOperations accessControlService,\n                              AuditLogService auditLogService,\n                              ObjectStorageService objectStorageService,\n                              StorageProperties storageProperties) {")
    src = src.replace("        this(jdbc, contractMapper, accessControlService, auditLogService, null, null, null, null);",
                      "        this(attachmentMapper, contractMapper, accessControlService, auditLogService, null, null, null, null);")
    src = src.replace("        this(jdbc, contractMapper, accessControlService, auditLogService,\n                objectStorageService, storageProperties, null, null);",
                      "        this(attachmentMapper, contractMapper, accessControlService, auditLogService,\n                objectStorageService, storageProperties, null, null);")

    old_list = '''        return jdbc.query(identityJoinSql("""
                        SELECT attachment.id, attachment.contract_id, attachment.uploader_company_id,
                               attachment.recipient_company_id, attachment.category, attachment.status,
                               attachment.original_name, attachment.content_type,
                               attachment.file_size, attachment.voucher_date, attachment.voucher_amount,
                               attachment.invoice_no, attachment.invoice_date, attachment.invoice_amount,
                               attachment.confirmed_at, attachment.rejected_reason,
                               attachment.signer_name, attachment.signed_at,
                               attachment.created_by, attachment.created_at,
                               company.name AS uploader_company_name,
                               COALESCE(user.nickname, user.phone, CONCAT('用户', attachment.created_by)) AS uploader_name
                        FROM contract_attachment attachment
                        LEFT JOIN company ON company.id = attachment.uploader_company_id
                        LEFT JOIN sys_user user ON user.id = attachment.created_by
                        WHERE attachment.contract_id = ? AND attachment.category = ?
                          AND attachment.deleted_at IS NULL
                        ORDER BY attachment.created_at DESC, attachment.id DESC
                        """), (rs, rowNum) -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", rs.getLong("id"));
                    view.put("contractId", rs.getLong("contract_id"));
                    view.put("uploaderCompanyId", rs.getLong("uploader_company_id"));
                    view.put("recipientCompanyId", rs.getObject("recipient_company_id", Long.class));
                    view.put("uploaderCompanyName", identityDirectory == null ? rs.getString("uploader_company_name")
                            : identityDirectory.companyNames(List.of(rs.getLong("uploader_company_id"))).get(rs.getLong("uploader_company_id")));
                    view.put("uploaderName", identityDirectory == null ? rs.getString("uploader_name")
                            : identityDirectory.userDisplayName(rs.getLong("created_by")));
                    view.put("category", rs.getString("category"));
                    String status = rs.getString("status");
                    Long recipientCompanyId = rs.getObject("recipient_company_id", Long.class);
                    boolean canConfirm = "PENDING_CONFIRMATION".equals(status)
                            && Long.valueOf(companyId).equals(recipientCompanyId);
                    view.put("status", status);
                    view.put("statusText", canConfirm ? "待我方确认" : statusText(status));
                    view.put("originalName", rs.getString("original_name"));
                    view.put("contentType", rs.getString("content_type"));
                    view.put("fileSize", rs.getLong("file_size"));
                    view.put("voucherDate", rs.getObject("voucher_date", LocalDate.class));
                    view.put("voucherAmount", rs.getBigDecimal("voucher_amount"));
                    view.put("invoiceNo", safe(rs.getString("invoice_no")));
                    view.put("invoiceDate", rs.getObject("invoice_date", LocalDate.class));
                    view.put("invoiceAmount", rs.getBigDecimal("invoice_amount"));
                    view.put("confirmedAt", rs.getTimestamp("confirmed_at") == null
                            ? null : rs.getTimestamp("confirmed_at").toLocalDateTime());
                    view.put("signerName", safe(rs.getString("signer_name")));
                    view.put("signedAt", rs.getTimestamp("signed_at") == null
                            ? null : rs.getTimestamp("signed_at").toLocalDateTime());
                    view.put("rejectedReason", safe(rs.getString("rejected_reason")));
                    view.put("canConfirm", canConfirm);
                    view.put("canResubmit", "REJECTED".equals(status)
                            && Long.valueOf(companyId).equals(rs.getLong("uploader_company_id")));
                    boolean uploader = Long.valueOf(companyId).equals(rs.getLong("uploader_company_id"))
                            && AuthContext.userId() == rs.getLong("created_by");
                    BilateralActionOperations.ActionState actionState = bilateralActionService == null
                            ? BilateralActionOperations.ActionState.empty()
                            : bilateralActionService.state(companyId,
                            BilateralActionOperations.ATTACHMENT, rs.getLong("id"));
                    view.put("contractReadOnly", contractReadOnly);
                    view.put("pendingActionId", actionState.id());
                    view.put("pendingActionType", actionState.actionType());
                    view.put("pendingActionReason", actionState.reason());
                    view.put("canReviewAction", actionState.approverCompany());
                    view.put("canCancelAction", actionState.requesterUser());
                    view.put("canWithdraw", !contractReadOnly && uploader && "PENDING_CONFIRMATION".equals(status));
                    view.put("canDelete", !contractReadOnly && uploader && ("REJECTED".equals(status)
                            || (OTHER.equals(rs.getString("category")) && "APPROVED".equals(status))));
                    view.put("canRequestVoid", !contractReadOnly && "APPROVED".equals(status)
                            && !OTHER.equals(rs.getString("category")));
                    view.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    return view;
                }, contractId, normalized);'''
    new_list = '''        List<ContractAttachmentDO> rows = identityDirectory == null
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
        return views;'''
    if old_list not in src:
        raise SystemExit("list() block not found")
    src = src.replace(old_list, new_list)

    old_insert = '''        if (stored == null) {
            jdbc.update("""
                    INSERT INTO contract_attachment
                    (id, contract_id, uploader_company_id, recipient_company_id, category, status,
                     original_name, content_type, file_size, file_data, sha256, voucher_date,
                     voucher_amount, invoice_no, invoice_date, invoice_amount, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, contractId, companyId, recipientCompanyId, normalized, status,
                    safeName, contentType, data.length, data, sha256, parsedDate, parsedAmount,
                    emptyToNull(safeInvoiceNo), parsedInvoiceDate, parsedInvoiceAmount, AuthContext.userId());
        } else {
            jdbc.update("""
                    INSERT INTO contract_attachment
                    (id, contract_id, uploader_company_id, recipient_company_id, category, status,
                     original_name, content_type, file_size, file_data, sha256, storage_provider,
                     storage_bucket, object_key, object_version_id, etag, encryption_algorithm,
                     voucher_date, voucher_amount, invoice_no, invoice_date, invoice_amount, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, contractId, companyId, recipientCompanyId, normalized, status,
                    safeName, contentType, data.length,
                    sha256, stored.provider(), stored.bucket(), stored.objectKey(), stored.versionId(),
                    stored.etag(), stored.encryptionAlgorithm(), parsedDate, parsedAmount,
                    emptyToNull(safeInvoiceNo), parsedInvoiceDate, parsedInvoiceAmount, AuthContext.userId());
        }'''
    new_insert = '''        ContractAttachmentDO row = new ContractAttachmentDO();
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
        attachmentMapper.insert(row);'''
    if old_insert not in src:
        raise SystemExit("insert block not found")
    src = src.replace(old_insert, new_insert)

    src = src.replace('''            jdbc.update("""
                    UPDATE contract_attachment
                    SET status = 'REJECTED', confirmed_by = ?, confirmed_at = CURRENT_TIMESTAMP,
                        rejected_reason = ?
                    WHERE id = ? AND status = 'PENDING_CONFIRMATION'
                    """, AuthContext.userId(), safeReason, id);''',
                      "            attachmentMapper.markRejected(id, AuthContext.userId(), safeReason);")

    src = src.replace('''        int updated = signature == null
                ? jdbc.update("""
                        UPDATE contract_attachment
                        SET status = 'APPROVED', confirmed_by = ?, confirmed_at = ?, rejected_reason = NULL
                        WHERE id = ? AND status = 'PENDING_CONFIRMATION'
                        """, AuthContext.userId(), confirmedAt, id)
                : updateApprovedWithSignature(id, confirmedAt, signature);''',
                      '''        int updated = signature == null
                ? attachmentMapper.markApproved(id, AuthContext.userId(), confirmedAt)
                : updateApprovedWithSignature(id, confirmedAt, signature);''')

    src = src.replace('''        if (stored == null) {
            return jdbc.update("""
                    UPDATE contract_attachment
                    SET status = 'APPROVED', confirmed_by = ?, confirmed_at = ?, rejected_reason = NULL,
                        signer_name = ?, signed_at = ?, signature_original_name = ?,
                        signature_content_type = ?, signature_file_size = ?, signature_data = ?,
                        signature_sha256 = ?, signature_storage_provider = NULL,
                        signature_storage_bucket = NULL, signature_object_key = NULL,
                        signature_object_version_id = NULL, signature_etag = NULL,
                        signature_encryption_algorithm = NULL
                    WHERE id = ? AND status = 'PENDING_CONFIRMATION'
                    """, AuthContext.userId(), confirmedAt, signature.signerName(), signature.signedAt(),
                    signature.originalName(), signature.contentType(), signature.fileSize(),
                    signature.data(), signature.sha256(), id);
        }
        return jdbc.update("""
                UPDATE contract_attachment
                SET status = 'APPROVED', confirmed_by = ?, confirmed_at = ?, rejected_reason = NULL,
                    signer_name = ?, signed_at = ?, signature_original_name = ?,
                    signature_content_type = ?, signature_file_size = ?, signature_data = NULL,
                    signature_sha256 = ?, signature_storage_provider = ?,
                    signature_storage_bucket = ?, signature_object_key = ?,
                    signature_object_version_id = ?, signature_etag = ?,
                    signature_encryption_algorithm = ?
                WHERE id = ? AND status = 'PENDING_CONFIRMATION'
                """, AuthContext.userId(), confirmedAt, signature.signerName(), signature.signedAt(),
                signature.originalName(), signature.contentType(), signature.fileSize(),
                signature.sha256(), stored.provider(), stored.bucket(), stored.objectKey(),
                stored.versionId(), stored.etag(), stored.encryptionAlgorithm(), id);''',
                      '''        if (stored == null) {
            return attachmentMapper.markApprovedWithInlineSignature(id, AuthContext.userId(), confirmedAt,
                    signature.signerName(), signature.signedAt(), signature.originalName(),
                    signature.contentType(), signature.fileSize(), signature.data(), signature.sha256());
        }
        return attachmentMapper.markApprovedWithStoredSignature(id, AuthContext.userId(), confirmedAt,
                signature.signerName(), signature.signedAt(), signature.originalName(),
                signature.contentType(), signature.fileSize(), signature.sha256(), stored.provider(),
                stored.bucket(), stored.objectKey(), stored.versionId(), stored.etag(),
                stored.encryptionAlgorithm());''')

    src = src.replace('''        int updated = jdbc.update("""
                UPDATE contract_attachment
                SET status = 'WITHDRAWN', deleted_by = ?, deleted_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING_CONFIRMATION' AND deleted_at IS NULL
                """, AuthContext.userId(), id);''',
                      "        int updated = attachmentMapper.markWithdrawn(id, AuthContext.userId());")
    src = src.replace('''        int updated = jdbc.update("""
                UPDATE contract_attachment
                SET deleted_by = ?, deleted_at = CURRENT_TIMESTAMP
                WHERE id = ? AND deleted_at IS NULL
                """, AuthContext.userId(), id);''',
                      "        int updated = attachmentMapper.markDeleted(id, AuthContext.userId());")

    src = src.replace('''        List<FilePayload> files = jdbc.query("""
                        SELECT id, contract_id, original_name, content_type, file_size, file_data, sha256,
                               storage_bucket, object_key, object_version_id
                        FROM contract_attachment WHERE id = ? AND deleted_at IS NULL
                          AND (? = 0 OR category = 'INVOICE')
                        """, (rs, rowNum) -> new FilePayload(
                        rs.getLong("id"), rs.getLong("contract_id"),
                        rs.getString("original_name"), rs.getString("content_type"),
                        rs.getBytes("file_data"), rs.getString("storage_bucket"),
                        rs.getString("object_key"), rs.getString("object_version_id"),
                        rs.getLong("file_size"), rs.getString("sha256")), id, invoiceOnly ? 1 : 0);
        if (files.isEmpty()) throw new BusinessException("附件不存在");
        FilePayload payload = files.get(0);''',
                      '''        ContractAttachmentDO file = attachmentMapper.selectFile(id, invoiceOnly ? 1 : 0);
        if (file == null) throw new BusinessException("附件不存在");
        FilePayload payload = new FilePayload(
                file.getId(), file.getContractId(), file.getOriginalName(), file.getContentType(),
                file.getFileData(), file.getStorageBucket(), file.getObjectKey(),
                file.getObjectVersionId(), file.getFileSize(), file.getSha256());''')

    src = src.replace('''        List<AttachmentRecord> rows = jdbc.query("""
                        SELECT id, contract_id, uploader_company_id, recipient_company_id,
                               category, status, original_name, voucher_date, voucher_amount,
                               invoice_no, invoice_date, invoice_amount, created_by
                        FROM contract_attachment WHERE id = ? AND deleted_at IS NULL
                        """, (rs, rowNum) -> new AttachmentRecord(
                        rs.getLong("id"), rs.getLong("contract_id"),
                        rs.getLong("uploader_company_id"),
                        rs.getObject("recipient_company_id", Long.class),
                        rs.getString("category"), rs.getString("status"),
                        rs.getString("original_name"),
                        rs.getObject("voucher_date", LocalDate.class),
                        rs.getBigDecimal("voucher_amount"), rs.getString("invoice_no"),
                        rs.getObject("invoice_date", LocalDate.class),
                        rs.getBigDecimal("invoice_amount"), rs.getLong("created_by")), id);
        if (rows.isEmpty()) throw new BusinessException("附件不存在");
        return rows.get(0);''',
                      '''        ContractAttachmentDO row = attachmentMapper.selectRecord(id);
        if (row == null) throw new BusinessException("附件不存在");
        return new AttachmentRecord(
                row.getId(), row.getContractId(), row.getUploaderCompanyId(),
                row.getRecipientCompanyId(), row.getCategory(), row.getStatus(),
                row.getOriginalName(), row.getVoucherDate(), row.getVoucherAmount(),
                row.getInvoiceNo(), row.getInvoiceDate(), row.getInvoiceAmount(),
                row.getCreatedBy());''')

    src = src.replace('''    private String identityJoinSql(String sql) {
        if (identityDirectory == null) return sql;
        return sql.replace("company.name AS uploader_company_name", "NULL AS uploader_company_name")
                .replace("COALESCE(user.nickname, user.phone, CONCAT('用户', attachment.created_by)) AS uploader_name", "NULL AS uploader_name")
                .replace("LEFT JOIN company ON company.id = attachment.uploader_company_id", "")
                .replace("LEFT JOIN sys_user user ON user.id = attachment.created_by", "");
    }

    ''', "")
    if "jdbc." in src:
        raise SystemExit("jdbc leftover in ContractAttachmentService")
    path.write_text(src)


def patch_statement():
    path = REC / "ReconciliationStatementService.java"
    src = path.read_text()
    src = src.replace("import org.springframework.jdbc.core.JdbcTemplate;\n",
                      "import com.tradepass.module.settlement.dal.dataobject.reconciliation.ReconciliationStatementDO;\n"
                      "import com.tradepass.module.settlement.dal.mysql.reconciliation.ReconciliationStatementMapper;\n")
    src = src.replace("    private final JdbcTemplate jdbc;", "    private final ReconciliationStatementMapper statementMapper;")
    src = src.replace("    public ReconciliationStatementService(JdbcTemplate jdbc,",
                      "    public ReconciliationStatementService(ReconciliationStatementMapper statementMapper,")
    src = src.replace("        this.jdbc = jdbc;", "        this.statementMapper = statementMapper;")
    src = src.replace("    ReconciliationStatementService(JdbcTemplate jdbc,",
                      "    ReconciliationStatementService(ReconciliationStatementMapper statementMapper,")
    src = src.replace("        this(jdbc, relationMapper, accessControlService, auditLogService, null, null);",
                      "        this(statementMapper, relationMapper, accessControlService, auditLogService, null, null);")
    old_insert = '''        if (stored == null) {
            jdbc.update("""
                    INSERT INTO reconciliation_statement
                    (id, issuer_company_id, counterparty_company_id, statement_period, original_name,
                     content_type, file_size, file_data, sha256, remark, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, companyId, counterpartyCompanyId, normalizedPeriod, safeName, contentType,
                    data.length, data, sha256, safeRemark, AuthContext.userId());
        } else {
            jdbc.update("""
                    INSERT INTO reconciliation_statement
                    (id, issuer_company_id, counterparty_company_id, statement_period, original_name,
                     content_type, file_size, file_data, sha256, storage_provider, storage_bucket,
                     object_key, object_version_id, etag, encryption_algorithm, remark, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, companyId, counterpartyCompanyId, normalizedPeriod, safeName, contentType,
                    data.length, sha256, stored.provider(), stored.bucket(), stored.objectKey(),
                    stored.versionId(), stored.etag(), stored.encryptionAlgorithm(), safeRemark,
                    AuthContext.userId());
        }'''
    new_insert = '''        ReconciliationStatementDO row = new ReconciliationStatementDO();
        row.setId(id);
        row.setIssuerCompanyId(companyId);
        row.setCounterpartyCompanyId(counterpartyCompanyId);
        row.setStatementPeriod(normalizedPeriod);
        row.setOriginalName(safeName);
        row.setContentType(contentType);
        row.setFileSize((long) data.length);
        row.setSha256(sha256);
        row.setRemark(safeRemark);
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
        statementMapper.insert(row);'''
    if old_insert not in src:
        raise SystemExit("statement insert not found")
    src = src.replace(old_insert, new_insert)
    src = src.replace('''        List<FilePayload> files = jdbc.query("""
                        SELECT id, issuer_company_id, counterparty_company_id,
                               original_name, content_type, file_size, file_data, sha256,
                               storage_bucket, object_key, object_version_id
                        FROM reconciliation_statement WHERE id = ?
                        """, (rs, rowNum) -> new FilePayload(
                        rs.getLong("id"), rs.getLong("issuer_company_id"),
                        rs.getLong("counterparty_company_id"), rs.getString("original_name"),
                        rs.getString("content_type"), rs.getBytes("file_data"),
                        rs.getString("storage_bucket"), rs.getString("object_key"),
                        rs.getString("object_version_id"), rs.getLong("file_size"),
                        rs.getString("sha256")), id);
        if (files.isEmpty()) throw new BusinessException("对账单不存在");
        FilePayload file = files.get(0);''',
                      '''        ReconciliationStatementDO storedFile = statementMapper.selectFile(id);
        if (storedFile == null) throw new BusinessException("对账单不存在");
        FilePayload file = new FilePayload(
                storedFile.getId(), storedFile.getIssuerCompanyId(), storedFile.getCounterpartyCompanyId(),
                storedFile.getOriginalName(), storedFile.getContentType(), storedFile.getFileData(),
                storedFile.getStorageBucket(), storedFile.getObjectKey(), storedFile.getObjectVersionId(),
                storedFile.getFileSize(), storedFile.getSha256());''')
    old_query = '''        String counterpartFilter = counterpartyCompanyId == null ? "" : """
                AND ((statement.issuer_company_id = ? AND statement.counterparty_company_id = ?)
                  OR (statement.issuer_company_id = ? AND statement.counterparty_company_id = ?))
                """;
        String sql = """
                SELECT statement.id, statement.issuer_company_id, statement.counterparty_company_id,
                       statement.statement_period, statement.original_name, statement.content_type,
                       statement.file_size, statement.remark, statement.created_at,
                       issuer.name AS issuer_company_name,
                       CASE WHEN statement.issuer_company_id = ? THEN counterparty.name ELSE issuer.name END AS counterparty_name
                FROM reconciliation_statement statement
                JOIN company issuer ON issuer.id = statement.issuer_company_id
                JOIN company counterparty ON counterparty.id = statement.counterparty_company_id
                WHERE (statement.issuer_company_id = ? OR statement.counterparty_company_id = ?)
                """ + counterpartFilter + " ORDER BY statement.statement_period DESC, statement.created_at DESC, statement.id DESC";
        Object[] args = counterpartyCompanyId == null
                ? new Object[]{companyId, companyId, companyId}
                : new Object[]{companyId, companyId, companyId,
                companyId, counterpartyCompanyId, counterpartyCompanyId, companyId};
        if (identityDirectory != null) {
            sql = sql.replace("issuer.name AS issuer_company_name", "NULL AS issuer_company_name")
                    .replace("THEN counterparty.name ELSE issuer.name END AS counterparty_name", "THEN NULL ELSE NULL END AS counterparty_name")
                    .replace("JOIN company issuer ON issuer.id = statement.issuer_company_id", "")
                    .replace("JOIN company counterparty ON counterparty.id = statement.counterparty_company_id", "");
        }
        return jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", rs.getLong("id"));
            view.put("issuerCompanyId", rs.getLong("issuer_company_id"));
            view.put("counterpartyCompanyId", rs.getLong("counterparty_company_id"));
            if (identityDirectory == null) {
                view.put("issuerCompanyName", rs.getString("issuer_company_name"));
                view.put("counterpartyName", rs.getString("counterparty_name"));
            } else {
                long issuer = rs.getLong("issuer_company_id"), counterparty = rs.getLong("counterparty_company_id");
                var names = identityDirectory.companyNames(List.of(issuer, counterparty));
                if (!names.containsKey(issuer) || !names.containsKey(counterparty)) return null;
                view.put("issuerCompanyName", names.get(issuer));
                view.put("counterpartyName", names.get(issuer == companyId ? counterparty : issuer));
            }
            view.put("statementPeriod", rs.getString("statement_period"));
            view.put("originalName", rs.getString("original_name"));
            view.put("contentType", rs.getString("content_type"));
            view.put("fileSize", rs.getLong("file_size"));
            view.put("remark", rs.getString("remark"));
            view.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
            return view;
        }, args).stream().filter(java.util.Objects::nonNull).toList();'''
    new_query = '''        List<ReconciliationStatementDO> rows;
        if (identityDirectory == null) {
            rows = counterpartyCompanyId == null
                    ? statementMapper.selectByCompany(companyId)
                    : statementMapper.selectByCompanyPair(companyId, counterpartyCompanyId);
        } else {
            rows = counterpartyCompanyId == null
                    ? statementMapper.selectByCompanyWithoutJoin(companyId)
                    : statementMapper.selectByCompanyPairWithoutJoin(companyId, counterpartyCompanyId);
        }
        return rows.stream().map(statement -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", statement.getId());
            view.put("issuerCompanyId", statement.getIssuerCompanyId());
            view.put("counterpartyCompanyId", statement.getCounterpartyCompanyId());
            if (identityDirectory == null) {
                view.put("issuerCompanyName", statement.getIssuerCompanyName());
                view.put("counterpartyName", statement.getCounterpartyName());
            } else {
                long issuer = statement.getIssuerCompanyId(), counterparty = statement.getCounterpartyCompanyId();
                var names = identityDirectory.companyNames(List.of(issuer, counterparty));
                if (!names.containsKey(issuer) || !names.containsKey(counterparty)) return null;
                view.put("issuerCompanyName", names.get(issuer));
                view.put("counterpartyName", names.get(issuer == companyId ? counterparty : issuer));
            }
            view.put("statementPeriod", statement.getStatementPeriod());
            view.put("originalName", statement.getOriginalName());
            view.put("contentType", statement.getContentType());
            view.put("fileSize", statement.getFileSize());
            view.put("remark", statement.getRemark());
            view.put("createdAt", statement.getCreatedAt());
            return view;
        }).filter(java.util.Objects::nonNull).toList();'''
    if old_query not in src:
        raise SystemExit("statement query not found")
    src = src.replace(old_query, new_query)
    if "jdbc." in src:
        raise SystemExit("jdbc leftover in ReconciliationStatementService")
    path.write_text(src)


def patch_account():
    path = REC / "ReconciliationAccountService.java"
    src = path.read_text()
    src = src.replace("import org.springframework.jdbc.core.JdbcTemplate;\n",
                      "import com.tradepass.module.settlement.dal.dataobject.reconciliation.ReconciliationEntryDO;\n"
                      "import com.tradepass.module.settlement.dal.mysql.reconciliation.ReconciliationEntryMapper;\n")
    src = src.replace("    private final JdbcTemplate jdbc;\n    private final CounterpartyReader relationMapper;",
                      "    private final JdbcTemplate jdbc;\n    private final ReconciliationEntryMapper entryMapper;\n    private final CounterpartyReader relationMapper;")
    src = src.replace("""    public ReconciliationAccountService(JdbcTemplate jdbc,
                                        CounterpartyReader relationMapper,
                                        AccessControlOperations accessControlService) {
        this.jdbc = jdbc;
        this.relationMapper = relationMapper;
        this.accessControlService = accessControlService;
    }""",
                      """    public ReconciliationAccountService(JdbcTemplate jdbc,
                                        ReconciliationEntryMapper entryMapper,
                                        CounterpartyReader relationMapper,
                                        AccessControlOperations accessControlService) {
        this.jdbc = jdbc;
        this.entryMapper = entryMapper;
        this.relationMapper = relationMapper;
        this.accessControlService = accessControlService;
    }""")
    src = src.replace('''        List<ReversalSource> sources = jdbc.query("""
                        SELECT id, company_a_id, company_b_id, contract_id, source_type,
                               business_date, document_no, amount, supplier_company_id,
                               buyer_company_id, issuer_company_id
                        FROM reconciliation_entry
                        WHERE source_type = ? AND source_id = ? AND reversal_of_id IS NULL
                        LIMIT 1
                        """, (rs, rowNum) -> new ReversalSource(
                        rs.getLong("id"), rs.getLong("company_a_id"), rs.getLong("company_b_id"),
                        rs.getLong("contract_id"), rs.getString("source_type"),
                        rs.getObject("business_date", LocalDate.class), rs.getString("document_no"),
                        rs.getBigDecimal("amount"), rs.getLong("supplier_company_id"),
                        rs.getLong("buyer_company_id"), rs.getLong("issuer_company_id")),
                sourceType, sourceId);
        if (sources.isEmpty()) return;
        ReversalSource source = sources.get(0);
        jdbc.update("""
                INSERT INTO reconciliation_entry
                (id, company_a_id, company_b_id, contract_id, source_type, source_id,
                 business_date, document_no, amount, supplier_company_id, buyer_company_id,
                 issuer_company_id, approved_by, approved_at, reversal_of_id, action_request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE reversal_of_id = VALUES(reversal_of_id)
                """, ApplicationIds.next(), source.companyAId(), source.companyBId(), source.contractId(),
                source.sourceType() + "_VOID", actionRequestId, source.businessDate(),
                safe(source.documentNo()) + "（作废冲销）", source.amount().negate(),
                source.supplierCompanyId(), source.buyerCompanyId(), source.issuerCompanyId(),
                approvedBy, approvedAt == null ? LocalDateTime.now() : approvedAt,
                source.id(), actionRequestId);''',
                      '''        ReconciliationEntryDO source = entryMapper.selectReversalSource(sourceType, sourceId);
        if (source == null) return;
        ReconciliationEntryDO reversal = new ReconciliationEntryDO();
        reversal.setId(ApplicationIds.next());
        reversal.setCompanyAId(source.getCompanyAId());
        reversal.setCompanyBId(source.getCompanyBId());
        reversal.setContractId(source.getContractId());
        reversal.setSourceType(source.getSourceType() + "_VOID");
        reversal.setSourceId(actionRequestId);
        reversal.setBusinessDate(source.getBusinessDate());
        reversal.setDocumentNo(safe(source.getDocumentNo()) + "（作废冲销）");
        reversal.setAmount(source.getAmount().negate());
        reversal.setSupplierCompanyId(source.getSupplierCompanyId());
        reversal.setBuyerCompanyId(source.getBuyerCompanyId());
        reversal.setIssuerCompanyId(source.getIssuerCompanyId());
        reversal.setApprovedBy(approvedBy);
        reversal.setApprovedAt(approvedAt == null ? LocalDateTime.now() : approvedAt);
        reversal.setReversalOfId(source.getId());
        reversal.setActionRequestId(actionRequestId);
        entryMapper.upsertReversal(reversal);''')
    src = src.replace('''        var ids = jdbc.queryForList("SELECT DISTINCT contract_id FROM reconciliation_entry WHERE company_a_id = ? AND company_b_id = ?",
                Long.class, companyAId, companyBId);''',
                      "        var ids = entryMapper.selectContractIds(companyAId, companyBId);")
    src = src.replace('''        jdbc.update("""
                INSERT INTO reconciliation_entry
                (id, company_a_id, company_b_id, contract_id, source_type, source_id,
                 business_date, document_no, amount, supplier_company_id, buyer_company_id,
                 issuer_company_id, approved_by, approved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE source_id = VALUES(source_id)
                """, ApplicationIds.next(), companyAId, companyBId, contractId, sourceType, sourceId,
                businessDate, safe(documentNo), money(amount), supplierCompanyId, buyerCompanyId,
                issuerCompanyId, approvedBy, approvedAt == null ? LocalDateTime.now() : approvedAt);''',
                      '''        ReconciliationEntryDO row = new ReconciliationEntryDO();
        row.setId(ApplicationIds.next());
        row.setCompanyAId(companyAId);
        row.setCompanyBId(companyBId);
        row.setContractId(contractId);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setBusinessDate(businessDate);
        row.setDocumentNo(safe(documentNo));
        row.setAmount(money(amount));
        row.setSupplierCompanyId(supplierCompanyId);
        row.setBuyerCompanyId(buyerCompanyId);
        row.setIssuerCompanyId(issuerCompanyId);
        row.setApprovedBy(approvedBy);
        row.setApprovedAt(approvedAt == null ? LocalDateTime.now() : approvedAt);
        entryMapper.upsertEntry(row);''')
    src = src.replace('''            var ids = jdbc.queryForList("SELECT DISTINCT contract_id FROM reconciliation_entry WHERE company_a_id = ? AND company_b_id = ?", Long.class, companyAId, companyBId);''',
                      "            var ids = entryMapper.selectContractIds(companyAId, companyBId);")
    src = src.replace('''        Map<Long, List<WorkbookEntry>> entriesByContract = new HashMap<>();
        jdbc.query("""
                        SELECT contract_id, source_type, business_date, amount
                        FROM reconciliation_entry
                        WHERE company_a_id = ? AND company_b_id = ?
                        ORDER BY contract_id, business_date, approved_at, id
                        """, rs -> {
                    entriesByContract.computeIfAbsent(rs.getLong("contract_id"),
                            ignored -> new ArrayList<>()).add(new WorkbookEntry(
                            rs.getString("source_type"),
                            rs.getObject("business_date", LocalDate.class),
                            rs.getBigDecimal("amount")));
                }, companyAId, companyBId);''',
                      '''        Map<Long, List<WorkbookEntry>> entriesByContract = new HashMap<>();
        for (ReconciliationEntryDO entry : entryMapper.selectWorkbookEntries(companyAId, companyBId)) {
            entriesByContract.computeIfAbsent(entry.getContractId(),
                    ignored -> new ArrayList<>()).add(new WorkbookEntry(
                    entry.getSourceType(), entry.getBusinessDate(), entry.getAmount()));
        }''')
    src = src.replace('''        List<Entry> entries = jdbc.query(accountEntrySql("""
                        SELECT entry.id, entry.contract_id, entry.source_type, entry.source_id,
                               entry.business_date, entry.document_no, entry.amount,
                               entry.supplier_company_id, entry.buyer_company_id,
                               entry.issuer_company_id, entry.approved_at,
                               contract.contract_no
                        FROM reconciliation_entry entry
                        LEFT JOIN trade_contract contract ON contract.id = entry.contract_id
                        WHERE entry.company_a_id = ? AND entry.company_b_id = ?
                        ORDER BY entry.business_date DESC, entry.approved_at DESC, entry.id DESC
                        """), (rs, rowNum) -> new Entry(
                        rs.getLong("id"), rs.getLong("contract_id"),
                        rs.getString("source_type"), rs.getLong("source_id"),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getString("document_no"), rs.getBigDecimal("amount"),
                        rs.getLong("supplier_company_id"), rs.getLong("buyer_company_id"),
                        rs.getLong("issuer_company_id"),
                        rs.getTimestamp("approved_at").toLocalDateTime(),
                        contractDirectory == null ? rs.getString("contract_no") : contractNumbers.get(rs.getLong("contract_id"))), companyAId, companyBId);''',
                      '''        List<ReconciliationEntryDO> rows = contractDirectory == null
                ? entryMapper.selectAccountEntriesWithContract(companyAId, companyBId)
                : entryMapper.selectAccountEntries(companyAId, companyBId);
        List<Entry> entries = rows.stream().map(row -> new Entry(
                        row.getId(), row.getContractId(),
                        row.getSourceType(), row.getSourceId(),
                        row.getBusinessDate(),
                        row.getDocumentNo(), row.getAmount(),
                        row.getSupplierCompanyId(), row.getBuyerCompanyId(),
                        row.getIssuerCompanyId(),
                        row.getApprovedAt(),
                        contractDirectory == null ? row.getContractNo() : contractNumbers.get(row.getContractId()))).toList();''')
    src = src.replace('''    private String accountEntrySql(String sql) {
        return contractDirectory == null ? sql : sql.replace("contract.contract_no", "NULL AS contract_no")
                .replace("LEFT JOIN trade_contract contract ON contract.id = entry.contract_id", "");
    }

    ''', "")
    # leftover jdbc is OK for company/contract fallbacks
    path.write_text(src)


if __name__ == "__main__":
    patch_attachment_state()
    patch_attachment_approval()
    patch_attachment_service()
    patch_statement()
    patch_account()
    print("settlement services patched")
