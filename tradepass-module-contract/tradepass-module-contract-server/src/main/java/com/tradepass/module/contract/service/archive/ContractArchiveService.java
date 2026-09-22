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

public interface ContractArchiveService {
    public record PdfRespVO(String fileName, byte[] data, String sha256) {
        }

    public record ArchiveRecord(Long id, Long contractId, Integer versionNo, String storageBucket,
                             String objectKey, String objectVersionId, String originalName,
                             String contentType, Long fileSize, String sha256) {
        }

    void archiveOnApproval(ContractRespDTO contract, long archivedBy);
    void archiveSignedPdf(ContractRespDTO contract, byte[] pdf, String providerFileId, long archivedBy);
    PdfRespVO getPdf(ContractRespDTO contract, long currentUserId);
}
