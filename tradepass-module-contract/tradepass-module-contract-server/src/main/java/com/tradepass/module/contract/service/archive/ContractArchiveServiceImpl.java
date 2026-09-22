package com.tradepass.module.contract.service.archive;

import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;

import com.tradepass.framework.mybatis.core.ApplicationIds;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.storage.config.StorageProperties;
import com.tradepass.module.contract.api.contract.dto.ContractRespDTO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ContractArchiveServiceImpl implements ContractArchiveService {
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final JdbcTemplate jdbc;
    private final ContractPdfService pdfService;
    private final ObjectStorageService objectStorageService;
    private final StorageProperties storageProperties;

    public ContractArchiveServiceImpl(JdbcTemplate jdbc, ContractPdfService pdfService,
                                  ObjectStorageService objectStorageService,
                                  StorageProperties storageProperties) {
        this.jdbc = jdbc;
        this.pdfService = pdfService;
        this.objectStorageService = objectStorageService;
        this.storageProperties = storageProperties;
    }

    public void archiveOnApproval(ContractRespDTO contract, long archivedBy) {
        if (!objectStorageService.isEnabled()) return;
        requireActive(contract);
        ensureArchive(contract, archivedBy);
    }

    public void archiveSignedPdf(ContractRespDTO contract, byte[] pdf, String providerFileId, long archivedBy) {
        if (!objectStorageService.isEnabled()) throw new BusinessException("签署文件归档存储尚未启用");
        if (pdf == null || pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F') {
            throw new BusinessException("签署文件格式不正确");
        }
        long contractId = parseId(contract.id());
        int versionNo = contract.versionNo() == null ? 1 : contract.versionNo();
        if (find(contractId, versionNo) != null) return;
        String sha256 = FileTypeInspector.sha256(pdf);
        long companyId = parseId(contract.companyId());
        String objectKey = keyPrefix() + "/contract/" + companyId + "/" + contractId
                + "/v" + versionNo + "/signed-" + sha256 + ".pdf";
        ObjectStorageService.StoredObject stored = objectStorageService.putImmutable(
                objectKey, pdf, PDF_CONTENT_TYPE, sha256);
        try {
            jdbc.update("""
                    INSERT INTO contract_archive
                    (id, contract_id, version_no, storage_provider, storage_bucket, object_key,
                     object_version_id, etag, original_name, content_type, file_size, sha256,
                     encryption_algorithm, archived_by, archive_source, provider_file_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FADADA_SIGNED', ?)
                    """, ApplicationIds.next(), contractId, versionNo, stored.provider(), stored.bucket(), stored.objectKey(),
                    stored.versionId(), stored.etag(), pdfService.fileName(contract), PDF_CONTENT_TYPE,
                    stored.fileSize(), stored.sha256(), stored.encryptionAlgorithm(), archivedBy,
                    providerFileId);
        } catch (DuplicateKeyException race) {
            if (find(contractId, versionNo) == null) throw race;
        }
    }

    public PdfRespVO getPdf(ContractRespDTO contract, long currentUserId) {
        if (!objectStorageService.isEnabled()) {
            byte[] generated = pdfService.generate(contract);
            return new PdfRespVO(pdfService.fileName(contract), generated, FileTypeInspector.sha256(generated));
        }
        long contractId = parseId(contract.id());
        int versionNo = contract.versionNo() == null ? 1 : contract.versionNo();
        ArchiveRecord archive = find(contractId, versionNo);
        if (archive == null && "ACTIVE".equals(contract.status())) {
            archive = ensureArchive(contract, currentUserId);
        }
        if (archive == null) {
            byte[] generated = pdfService.generate(contract);
            return new PdfRespVO(pdfService.fileName(contract), generated, FileTypeInspector.sha256(generated));
        }
        byte[] data = objectStorageService.get(new ObjectStorageService.ObjectReference(
                archive.storageBucket(), archive.objectKey(), archive.objectVersionId(),
                archive.fileSize(), archive.sha256()));
        return new PdfRespVO(archive.originalName(), data, archive.sha256());
    }

    private ArchiveRecord ensureArchive(ContractRespDTO contract, long archivedBy) {
        long contractId = parseId(contract.id());
        int versionNo = contract.versionNo() == null ? 1 : contract.versionNo();
        ArchiveRecord existing = find(contractId, versionNo);
        if (existing != null) return existing;

        byte[] pdf = pdfService.generate(contract);
        String sha256 = FileTypeInspector.sha256(pdf);
        long companyId = parseId(contract.companyId());
        String objectKey = keyPrefix() + "/contract/" + companyId + "/" + contractId
                + "/v" + versionNo + "/" + sha256 + ".pdf";
        ObjectStorageService.StoredObject stored = objectStorageService.putImmutable(
                objectKey, pdf, PDF_CONTENT_TYPE, sha256);
        try {
            jdbc.update("""
                    INSERT INTO contract_archive
                    (id, contract_id, version_no, storage_provider, storage_bucket, object_key,
                     object_version_id, etag, original_name, content_type, file_size, sha256,
                     encryption_algorithm, archived_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, ApplicationIds.next(), contractId, versionNo, stored.provider(), stored.bucket(), stored.objectKey(),
                    stored.versionId(), stored.etag(), pdfService.fileName(contract), PDF_CONTENT_TYPE,
                    stored.fileSize(), stored.sha256(), stored.encryptionAlgorithm(), archivedBy);
        } catch (DuplicateKeyException race) {
            ArchiveRecord winner = find(contractId, versionNo);
            if (winner != null) return winner;
            throw race;
        }
        ArchiveRecord created = find(contractId, versionNo);
        if (created == null) throw new BusinessException("合同归档记录保存失败");
        return created;
    }

    private ArchiveRecord find(long contractId, int versionNo) {
        List<ArchiveRecord> rows = jdbc.query("""
                        SELECT id, contract_id, version_no, storage_bucket, object_key,
                               object_version_id, original_name, content_type, file_size, sha256
                        FROM contract_archive
                        WHERE contract_id = ? AND version_no = ?
                        LIMIT 1
                        """, (rs, rowNum) -> new ArchiveRecord(
                        rs.getLong("id"), rs.getLong("contract_id"), rs.getInt("version_no"),
                        rs.getString("storage_bucket"), rs.getString("object_key"),
                        rs.getString("object_version_id"), rs.getString("original_name"),
                        rs.getString("content_type"), rs.getLong("file_size"),
                        rs.getString("sha256")), contractId, versionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void requireActive(ContractRespDTO contract) {
        if (!"ACTIVE".equals(contract.status())) {
            throw new BusinessException("只有已生效合同才能归档");
        }
    }

    private long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (Exception exception) {
            throw new BusinessException("合同标识不正确");
        }
    }

    private String keyPrefix() {
        String value = storageProperties.getKeyPrefix();
        value = value == null ? "" : value.trim().replaceAll("^/+|/+$", "");
        return value.isBlank() ? "tradepass" : value;
    }

    

    
}
