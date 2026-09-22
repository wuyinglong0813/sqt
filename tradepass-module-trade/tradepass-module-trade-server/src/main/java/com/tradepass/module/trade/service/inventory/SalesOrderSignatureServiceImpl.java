package com.tradepass.module.trade.service.inventory;

import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.storage.config.StorageProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class SalesOrderSignatureServiceImpl implements SalesOrderSignatureService {
    static final long MAX_SIGNATURE_SIZE = 2L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final ObjectStorageService objectStorageService;
    private final StorageProperties storageProperties;

    public SalesOrderSignatureServiceImpl(JdbcTemplate jdbc,
                                      ObjectStorageService objectStorageService,
                                      StorageProperties storageProperties) {
        this.jdbc = jdbc;
        this.objectStorageService = objectStorageService;
        this.storageProperties = storageProperties;
    }

    public Confirmation save(long companyId, Long documentId, Long receiptId,
                             String signerName, String originalName, byte[] data) {
        if (data == null || data.length == 0) {
            throw new BusinessException("请先完成手写签名");
        }
        if (data.length > MAX_SIGNATURE_SIZE) {
            throw new BusinessException("签名图片不能超过 2MB");
        }
        String contentType = FileTypeInspector.inspect(data);
        if (!"image/png".equals(contentType) && !"image/jpeg".equals(contentType)) {
            throw new BusinessException("签名仅支持 PNG 或 JPG 图片");
        }
        String safeName = FileTypeInspector.sanitizeFileName(originalName, contentType);
        String sha256 = FileTypeInspector.sha256(data);
        ObjectStorageService.StoredObject stored = store(
                companyId, documentId, contentType, data, sha256);
        LocalDateTime signedAt = LocalDateTime.now();
        int updated;
        if (stored == null) {
            updated = jdbc.update("""
                    UPDATE sales_order_receipt
                    SET signer_name = ?, signed_at = ?, signature_original_name = ?,
                        signature_content_type = ?, signature_file_size = ?, signature_data = ?,
                        signature_sha256 = ?, signature_storage_provider = NULL,
                        signature_storage_bucket = NULL, signature_object_key = NULL,
                        signature_object_version_id = NULL, signature_etag = NULL,
                        signature_encryption_algorithm = NULL
                    WHERE id = ? AND company_id = ? AND document_id = ?
                    """, signerName, signedAt, safeName, contentType, data.length, data, sha256,
                    receiptId, companyId, documentId);
        } else {
            updated = jdbc.update("""
                    UPDATE sales_order_receipt
                    SET signer_name = ?, signed_at = ?, signature_original_name = ?,
                        signature_content_type = ?, signature_file_size = ?, signature_data = NULL,
                        signature_sha256 = ?, signature_storage_provider = ?,
                        signature_storage_bucket = ?, signature_object_key = ?,
                        signature_object_version_id = ?, signature_etag = ?,
                        signature_encryption_algorithm = ?
                    WHERE id = ? AND company_id = ? AND document_id = ?
                    """, signerName, signedAt, safeName, contentType, data.length, sha256,
                    stored.provider(), stored.bucket(), stored.objectKey(), stored.versionId(),
                    stored.etag(), stored.encryptionAlgorithm(), receiptId, companyId, documentId);
        }
        if (updated != 1) throw new BusinessException("销售单签名保存失败，请重试");
        return new Confirmation(signerName, signedAt, safeName, contentType, data);
    }

    public Confirmation find(Long documentId) {
        List<SignatureRecord> rows = jdbc.query("""
                        SELECT signer_name, signed_at, signature_original_name,
                               signature_content_type, signature_file_size, signature_data,
                               signature_sha256, signature_storage_bucket,
                               signature_object_key, signature_object_version_id
                        FROM sales_order_receipt
                        WHERE document_id = ? AND signer_name IS NOT NULL AND signed_at IS NOT NULL
                        ORDER BY id DESC LIMIT 1
                        """, (rs, rowNum) -> new SignatureRecord(
                        rs.getString("signer_name"),
                        rs.getTimestamp("signed_at").toLocalDateTime(),
                        rs.getString("signature_original_name"),
                        rs.getString("signature_content_type"),
                        rs.getObject("signature_file_size", Long.class),
                        rs.getBytes("signature_data"),
                        rs.getString("signature_sha256"),
                        rs.getString("signature_storage_bucket"),
                        rs.getString("signature_object_key"),
                        rs.getString("signature_object_version_id")), documentId);
        if (rows.isEmpty()) return null;
        SignatureRecord row = rows.get(0);
        byte[] data = row.data();
        if (data == null) {
            if (objectStorageService == null || !objectStorageService.isEnabled()
                    || row.objectKey() == null || row.storageBucket() == null) {
                throw new BusinessException("销售单签名暂不可用，请联系管理员");
            }
            data = objectStorageService.get(new ObjectStorageService.ObjectReference(
                    row.storageBucket(), row.objectKey(), row.objectVersionId(),
                    row.fileSize(), row.sha256()));
        }
        return new Confirmation(row.signerName(), row.signedAt(), row.originalName(),
                row.contentType(), data);
    }

    private ObjectStorageService.StoredObject store(long companyId, Long documentId,
                                                     String contentType, byte[] data, String sha256) {
        if (objectStorageService == null || !objectStorageService.isEnabled()) return null;
        LocalDate today = LocalDate.now();
        String key = keyPrefix() + "/file/" + companyId + "/" + documentId
                + "/customer-signature/" + today.getYear() + "/"
                + String.format("%02d", today.getMonthValue()) + "/"
                + UUID.randomUUID() + "-" + sha256 + "."
                + FileTypeInspector.extension(contentType);
        return objectStorageService.putImmutable(key, data, contentType, sha256);
    }

    private String keyPrefix() {
        String value = storageProperties == null ? "tradepass" : storageProperties.getKeyPrefix();
        value = value == null ? "" : value.trim().replaceAll("^/+|/+$", "");
        return value.isBlank() ? "tradepass" : value;
    }

    

    private record SignatureRecord(String signerName, LocalDateTime signedAt,
                                   String originalName, String contentType, Long fileSize,
                                   byte[] data, String sha256, String storageBucket,
                                   String objectKey, String objectVersionId) {
    }
}
