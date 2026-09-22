package com.tradepass.module.settlement.api.reconciliation;

import com.tradepass.framework.common.util.FileTypeInspector;
import java.util.List;
import java.util.Map;

/** In-process domain contract; implementations retain the original transaction semantics. */
public interface ReconciliationStatementOperations {
    public List<Map<String, Object>> list(Long counterpartyCompanyId);

    public Map<String, Object> upload(Long counterpartyCompanyId, String period, String remark,
                                      String originalName, byte[] data);

    public FileRespDTO getFile(Long id);

    public record FileRespDTO(Long id, Long issuerCompanyId, Long counterpartyCompanyId,
                              String originalName, String contentType, byte[] data,
                              String storageBucket, String objectKey, String objectVersionId,
                              Long fileSize, String sha256) {
        public FileRespDTO(Long id, Long issuerCompanyId, Long counterpartyCompanyId,
                           String originalName, String contentType, byte[] data) {
            this(id, issuerCompanyId, counterpartyCompanyId, originalName, contentType, data,
                    null, null, null, data == null ? null : (long) data.length,
                    data == null ? null : FileTypeInspector.sha256(data));
        }

        public FileRespDTO withData(byte[] value) {
            return new FileRespDTO(id, issuerCompanyId, counterpartyCompanyId,
                    originalName, contentType, value, storageBucket, objectKey,
                    objectVersionId, fileSize, sha256);
        }
    }
}
